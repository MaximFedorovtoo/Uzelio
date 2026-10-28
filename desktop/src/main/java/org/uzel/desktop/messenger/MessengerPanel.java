package org.uzel.desktop.messenger;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import javax.sound.sampled.*;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatInvite;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatWire;
import org.uzel.messenger.chat.LocalAddress;

/** Native controls; the composer is never replaced by the periodic history refresh. */
public final class MessengerPanel extends JPanel implements AutoCloseable,Scrollable {
    private static final Color BG=new Color(0x081524),SURFACE=new Color(0x122A40),TEXT=new Color(0xEDF7FF),MUTED=new Color(0xA5BBCF),CYAN=new Color(0x28E2DD);
    private static final int MAX_FILE=8*1024*1024;
    /** Glyphs "📎", "🎤", "▶" and "■" are missing from Segoe UI but present here. */
    private static final String ICON_FONT="Segoe UI Symbol";
    private final Path root;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-chat-ui");t.setDaemon(true);return t;});
    private final JTextField search=new JTextField();
    private final JTextArea draft=new BubbleArea("");
    private final JPanel contacts=column(),messages=column(),conversation=new JPanel(new BorderLayout(8,8));
    private final JLabel status=new JLabel("Добавьте контакт по приглашению"),heading=new JLabel("Выберите контакт для переписки");
    private final JButton send=button("Отправить"),retry=button("Повторить подключение"),back=button("‹"),attach=button("📎"),mic=button("🎤");
    private final JLabel activeAvatar=new AvatarLabel("");
    private final JButton call=smallButton("Позвонить");
    private final JPanel callPanel=column(),callActions=new JPanel(new FlowLayout(FlowLayout.LEFT,8,6));
    private final JLabel callTitle=new JLabel();
    private final JButton acceptCall=smallButton("Принять"),rejectCall=smallButton("Отклонить"),hangup=smallButton("Завершить"),dismissCall=smallButton("Скрыть");
    private final CallSession.Listener callListener=new CallSession.Listener(){
        public void state(CallSession source,CallSession.State state,String contactId,String name){
            SwingUtilities.invokeLater(()->{if(!closed&&source==callSession)renderCall(state,contactId,name);});
        }
        public void error(CallSession source,String message){
            SwingUtilities.invokeLater(()->{if(!closed&&source==callSession&&message!=null&&!message.isBlank())status.setText(message);});
        }
    };
    private CallSession callSession;
    private String callContact="",callName="",callCallId="",callOffer="";
    private volatile VoiceRecorder recorder;
    private volatile String playbackId="";
    private volatile Clip playbackClip;
    private final Map<String,String> drafts=new HashMap<>();
    private java.util.List<ChatRepository.Contact> cached=java.util.List.of();
    private String selected="",signature="";
    private volatile ChatService service;
    private boolean pending;
    private volatile boolean closed;
    public MessengerPanel(Path root){
        this.root=root;setBackground(BG);setLayout(new BorderLayout());
        JPanel sidebar=new JPanel(new BorderLayout(0,14));sidebar.setBackground(new Color(0x0C1E30));sidebar.setBorder(new EmptyBorder(20,18,16,18));
        JPanel tools=column();tools.setOpaque(false);JPanel titleRow=new JPanel(new BorderLayout(10,0));titleRow.setOpaque(false);
        JLabel sectionTitle=label("Чаты");sectionTitle.setFont(new Font("Segoe UI",Font.BOLD,24));titleRow.add(sectionTitle,BorderLayout.CENTER);
        JButton add=button("+");add.setToolTipText("Добавить контакт");add.getAccessibleContext().setAccessibleName("Добавить контакт");titleRow.add(add,BorderLayout.EAST);tools.add(titleRow);tools.add(Box.createVerticalStrut(16));
        style(search);search.setBackground(BG);search.setBorder(BorderFactory.createCompoundBorder(new LineBorder(new Color(0x294257),1,true),new EmptyBorder(10,12,10,12)));search.setToolTipText("Поиск по чатам");search.getAccessibleContext().setAccessibleName("Поиск по чатам");tools.add(label("Поиск по чатам"));tools.add(search);sidebar.add(tools,BorderLayout.NORTH);sidebar.add(scroll(contacts),BorderLayout.CENTER);
        JPanel footer=column();footer.setOpaque(false);JLabel privacyTitle=label("Ваши разговоры — у вас");privacyTitle.setFont(new Font("Segoe UI",Font.BOLD,13));footer.add(privacyTitle);JTextArea privacy=new JTextArea("История хранится только на устройствах участников.",3,20);style(privacy);privacy.setOpaque(false);privacy.setEditable(false);privacy.setLineWrap(true);privacy.setWrapStyleWord(true);footer.add(privacy);JButton invite=button("Моё приглашение");footer.add(invite);status.setFont(new Font("Segoe UI",Font.PLAIN,11));status.setForeground(MUTED);footer.add(status);retry.setVisible(false);retry.addActionListener(e->{if(service==null)refresh();else work(()->service.retryNow());});footer.add(retry);sidebar.add(footer,BorderLayout.SOUTH);
        JPanel list=sidebar;
        JPanel center=new JPanel(null){public void doLayout(){int w=getWidth(),h=getHeight();boolean wide=w>700;back.setVisible(!wide);list.setVisible(wide||selected.isEmpty());conversation.setVisible(wide||!selected.isEmpty());if(wide){list.setBounds(0,0,320,h);conversation.setBounds(320,0,Math.max(0,w-320),h);}else{list.setBounds(0,0,w,h);conversation.setBounds(0,0,w,h);}}};center.setOpaque(false);center.add(list);center.add(conversation);add(center,BorderLayout.CENTER);
        conversation.setBackground(BG);conversation.setBorder(new EmptyBorder(16,24,14,24));
        JPanel title=column();JPanel head=new JPanel(new BorderLayout(10,0));head.setOpaque(false);back.setToolTipText("Назад к чатам");back.addActionListener(e->{drafts.put(selected,draft.getText());selected="";updateCallButton();revalidate();repaint();});head.add(back,BorderLayout.WEST);heading.setForeground(TEXT);heading.setFont(new Font("Segoe UI",Font.BOLD,17));JPanel person=new JPanel(new BorderLayout(12,0));person.setOpaque(false);activeAvatar.setPreferredSize(new Dimension(42,42));person.add(activeAvatar,BorderLayout.WEST);person.add(heading,BorderLayout.CENTER);head.add(person,BorderLayout.CENTER);
        call.setEnabled(false);call.setToolTipText("Позвонить контакту");call.getAccessibleContext().setAccessibleName("Позвонить контакту");call.addActionListener(e->startCall());head.add(call,BorderLayout.EAST);title.add(head);
        JLabel route=new JLabel("Личная переписка · сквозное шифрование",SwingConstants.CENTER);route.setForeground(MUTED);route.setFont(new Font("Segoe UI",Font.PLAIN,11));route.setBorder(new EmptyBorder(14,0,14,0));title.add(route);
        callTitle.setForeground(TEXT);callTitle.setFont(new Font("Segoe UI",Font.PLAIN,12));callPanel.setOpaque(false);callPanel.setBorder(new EmptyBorder(0,0,10,0));callPanel.setVisible(false);callPanel.add(callTitle);callActions.setOpaque(false);
        acceptCall.setToolTipText("Принять звонок");acceptCall.getAccessibleContext().setAccessibleName("Принять звонок");acceptCall.addActionListener(e->acceptIncoming());
        rejectCall.setToolTipText("Отклонить звонок");rejectCall.getAccessibleContext().setAccessibleName("Отклонить звонок");rejectCall.addActionListener(e->rejectIncoming());
        hangup.setToolTipText("Завершить звонок");hangup.getAccessibleContext().setAccessibleName("Завершить звонок");hangup.addActionListener(e->hangupCall());
        dismissCall.setToolTipText("Скрыть сообщение о звонке");dismissCall.getAccessibleContext().setAccessibleName("Скрыть сообщение о звонке");dismissCall.addActionListener(e->dismissCallPanel());
        callActions.add(acceptCall);callActions.add(rejectCall);callActions.add(hangup);callActions.add(dismissCall);callPanel.add(callActions);title.add(callPanel);
        conversation.add(title,BorderLayout.NORTH);messages.setBorder(new EmptyBorder(8,8,12,8));conversation.add(scroll(messages),BorderLayout.CENTER);
        JPanel composer=new JPanel(new BorderLayout(10,7));composer.setOpaque(false);composer.setBorder(new EmptyBorder(12,0,0,0));draft.setRows(1);draft.setLineWrap(true);draft.setWrapStyleWord(true);style(draft);draft.getAccessibleContext().setAccessibleName("Сообщение");JScrollPane input=scroll(draft);input.setPreferredSize(new Dimension(100,44));composer.add(input,BorderLayout.CENTER);send.setPreferredSize(new Dimension(48,44));send.setText("➤");send.setToolTipText("Отправить");send.getAccessibleContext().setAccessibleName("Отправить");
        attach.setPreferredSize(new Dimension(44,44));attach.setBorder(new EmptyBorder(8,8,8,8));attach.setFont(new Font(ICON_FONT,Font.PLAIN,17));attach.setToolTipText("Отправить файл");attach.getAccessibleContext().setAccessibleName("Отправить файл");attach.addActionListener(e->chooseFile());
        mic.setPreferredSize(new Dimension(44,44));mic.setBorder(new EmptyBorder(8,8,8,8));mic.setFont(new Font(ICON_FONT,Font.PLAIN,17));mic.setToolTipText("Голосовое сообщение");mic.getAccessibleContext().setAccessibleName("Голосовое сообщение");mic.addActionListener(e->toggleVoice());
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));actions.setOpaque(false);actions.add(attach);actions.add(mic);actions.add(send);composer.add(actions,BorderLayout.EAST);JLabel hint=new JLabel("Сообщение дождётся соединения ваших устройств",SwingConstants.CENTER);hint.setFont(new Font("Segoe UI",Font.PLAIN,10));hint.setForeground(MUTED);composer.add(hint,BorderLayout.SOUTH);conversation.add(composer,BorderLayout.SOUTH);send.setEnabled(false);send.addActionListener(e->send());
        search.getDocument().addDocumentListener(change(this::renderContacts));draft.getDocument().addDocumentListener(change(()->{drafts.put(selected,draft.getText());send.setEnabled(service!=null&&!selected.isEmpty()&&!draft.getText().trim().isEmpty()&&!pending);}));add.addActionListener(e->addContact());invite.addActionListener(e->invitation());renderContacts();showText("Выберите контакт для переписки");
    }
    private static DocumentListener change(Runnable action){return new DocumentListener(){public void insertUpdate(DocumentEvent e){action.run();}public void removeUpdate(DocumentEvent e){action.run();}public void changedUpdate(DocumentEvent e){action.run();}};}
    public void refresh(){if(pending||closed)return;pending=true;String id=selected;submit(()->{try{if(service==null)service=DesktopMessenger.chats(root).get();var list=service.contacts();var history=id.isEmpty()?java.util.List.<ChatRepository.Message>of():service.history(id);postUi(()->{if(closed)return;pending=false;cached=list;retry.setVisible(false);status.setText(recorder!=null?"Запись…":"История остаётся на устройствах участников");renderContacts();if(selected.equals(id))renderHistory(history);send.setEnabled(!selected.isEmpty()&&!draft.getText().trim().isEmpty());updateCallButton();});}catch(Exception e){postUi(()->{pending=false;status.setText("Чаты недоступны. Повторите после разблокировки устройства.");retry.setVisible(true);});}});}
    private void renderContacts(){contacts.setBackground(new Color(0x0C1E30));contacts.removeAll();String q=search.getText().trim().toLowerCase(Locale.ROOT);int count=0;for(var c:cached){if(!c.name().toLowerCase(Locale.ROOT).contains(q))continue;count++;
        JButton row=button("");row.setLayout(new BorderLayout(12,0));row.setAlignmentX(LEFT_ALIGNMENT);row.setMaximumSize(new Dimension(Integer.MAX_VALUE,76));row.setPreferredSize(new Dimension(260,76));row.setBackground(c.id().equals(selected)?new Color(0x21465B):SURFACE);
        JLabel avatar=new AvatarLabel(initials(c.name()));avatar.setHorizontalAlignment(SwingConstants.CENTER);avatar.setFont(new Font("Segoe UI",Font.BOLD,16));avatar.setOpaque(false);avatar.setBackground(new Color(0x247675));avatar.setPreferredSize(new Dimension(46,46));row.add(avatar,BorderLayout.WEST);JPanel copy=column();copy.setOpaque(false);JLabel name=label(c.name());name.setFont(new Font("Segoe UI",Font.BOLD,14));copy.add(name);JLabel hint=label("Личная переписка");hint.setFont(new Font("Segoe UI",Font.PLAIN,12));hint.setForeground(MUTED);copy.add(hint);row.add(copy,BorderLayout.CENTER);row.getAccessibleContext().setAccessibleName(c.name());
        row.addActionListener(e->{drafts.put(selected,draft.getText());selected=c.id();heading.setText(c.name());activeAvatar.setText(initials(c.name()));draft.setText(drafts.getOrDefault(selected,""));signature="";updateCallButton();showText("Загружаем историю…");revalidate();repaint();refresh();});contacts.add(row);contacts.add(Box.createVerticalStrut(6));}
        if(count==0){JTextArea empty=new JTextArea(q.isEmpty()?"Пока нет чатов. Добавьте контакт по приглашению.":"Контакты не найдены. Попробуйте другое имя.");style(empty);empty.setOpaque(false);empty.setForeground(MUTED);empty.setEditable(false);empty.setLineWrap(true);empty.setWrapStyleWord(true);contacts.add(empty);}contacts.revalidate();contacts.repaint();}
    private static String initials(String name){StringBuilder b=new StringBuilder();for(String part:name.trim().split("\\s+")){if(!part.isEmpty())b.appendCodePoint(part.codePointAt(0));if(b.codePointCount(0,b.length())==2)break;}return b.toString().toUpperCase(Locale.ROOT);}
    private void renderHistory(java.util.List<ChatRepository.Message> history){
        String next=history.toString();if(next.equals(signature))return;signature=next;messages.removeAll();
        if(history.isEmpty())showText(selected.isEmpty()?"Выберите контакт для переписки":"Сообщений пока нет");
        else for(var m:history){
            JPanel row=new JPanel(new FlowLayout(m.outgoing()?FlowLayout.RIGHT:FlowLayout.LEFT,0,5));row.setOpaque(false);
            JPanel bubble=new BubblePanel(m.outgoing()?new Color(0x163E50):new Color(0x132B40));bubble.setLayout(new BorderLayout(0,6));bubble.setBorder(new EmptyBorder(10,14,10,14));
            if(m.attachment()!=null)bubble.add(attachmentBody(m),BorderLayout.CENTER);
            else {JTextArea body=new JTextArea(m.text());style(body);body.setOpaque(false);body.setEditable(false);body.setBorder(null);body.setLineWrap(true);body.setWrapStyleWord(true);body.setColumns(24);body.setSize(new Dimension(280,Integer.MAX_VALUE));body.setPreferredSize(new Dimension(280,body.getPreferredSize().height));bubble.add(body,BorderLayout.CENTER);}
            JLabel metadata=new JLabel(new SimpleDateFormat("HH:mm").format(new Date(m.timeMillis()))+(m.outgoing()?" · "+state(m.status()):""),SwingConstants.RIGHT);metadata.setFont(new Font("Segoe UI",Font.PLAIN,10));metadata.setForeground(new Color(0x9ABAC9));bubble.add(metadata,BorderLayout.SOUTH);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE,bubble.getPreferredSize().height+10));row.add(bubble);messages.add(row);
        }
        messages.add(Box.createVerticalGlue());messages.revalidate();messages.repaint();
    }
    private static final class BubblePanel extends JPanel {
        BubblePanel(Color color){setBackground(color);setOpaque(false);}
        protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color base=getBackground();g.setPaint(new GradientPaint(0,0,base.brighter(),getWidth(),getHeight(),base));g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,17,17);g.setColor(new Color(0x355068));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,17,17);g.dispose();super.paintComponent(raw);}
    }
    private static final class AvatarLabel extends JLabel {AvatarLabel(String value){super(value,SwingConstants.CENTER);setForeground(TEXT);setFont(new Font("Segoe UI",Font.BOLD,16));setOpaque(false);}protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setPaint(new GradientPaint(0,0,new Color(0x247675),getWidth(),getHeight(),new Color(0x16454E)));g.fillRoundRect(0,0,getWidth(),getHeight(),16,16);g.dispose();super.paintComponent(raw);}}
    private static final class BubbleArea extends JTextArea {BubbleArea(String value){super(value);setOpaque(false);}protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);Color base=getBackground();g.setPaint(new GradientPaint(0,0,base.brighter(),getWidth(),getHeight(),base));g.fillRoundRect(0,0,getWidth()-1,getHeight()-1,17,17);g.setColor(new Color(0x355068));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,17,17);g.dispose();super.paintComponent(raw);}}
    private static String state(String s){return switch(s){case "delivered"->"Доставлено";case "sent"->"Отправлено";default->"Ожидает отправки";};}
    private void showText(String value){messages.removeAll();JLabel t=new JLabel(value);t.setForeground(MUTED);messages.add(t);messages.revalidate();messages.repaint();}
    private void send(){if(closed)return;String text=draft.getText().trim(),id=selected;if(text.isEmpty()||service==null||id.isEmpty())return;send.setEnabled(false);submit(()->{try{service.send(id,text);postUi(()->{if(selected.equals(id)&&draft.getText().trim().equals(text)){draft.setText("");drafts.put(id,"");}else if(drafts.getOrDefault(id,"").trim().equals(text))drafts.put(id,"");signature="";refresh();});}catch(Exception e){postUi(()->{status.setText("Сообщение не сохранено. Повторите отправку.");send.setEnabled(true);});}});}
    /** The microphone is opened only after this explicit click; nothing rings or dials by itself. */
    private void startCall(){
        if(closed||activeCall())return;
        if(selected.isEmpty()){status.setText("Выберите контакт для звонка");return;}
        ChatService current=service;
        if(current==null){status.setText("Чаты ещё не готовы");return;}
        releaseCall();
        callContact=selected;callName=nameFor(selected);callCallId="";callOffer="";
        CallSession session=new CallSession(callListener);callSession=session;
        showCall("Звоним… "+callName,false,false,true,false);updateCallButton();
        session.start(current,selected,callName);
    }
    /** Receives CALL_OFFER/CALL_ANSWER/CALL_END from DesktopMessenger on any thread. */
    public void deliverCall(ChatService source,String contactId,String name,int kind,String callId,String sdp){
        if(closed)return;
        Runnable delivery=()->handleCall(source,contactId,name,kind,callId,sdp);
        if(SwingUtilities.isEventDispatchThread())delivery.run();else SwingUtilities.invokeLater(()->{if(!closed)delivery.run();});
    }
    private void handleCall(ChatService source,String contact,String displayName,int kind,String id,String sdp){
        if(closed||source==null||contact==null||contact.isEmpty())return;
        service=source;
        if(kind==ChatWire.CALL_OFFER){
            if(activeCall()){status.setText("Пропущен входящий звонок: уже идёт звонок");return;}
            releaseCall();
            callContact=contact;callName=displayName==null||displayName.isBlank()?nameFor(contact):displayName;callCallId=id==null?"":id;callOffer=sdp==null?"":sdp;
            if(selected.isEmpty())selectContact(contact,callName);
            CallSession session=new CallSession(callListener);callSession=session;
            session.incoming(source,contact,callName,id,sdp);
            updateCallButton();
            return;
        }
        CallSession session=callSession;
        if(session!=null)session.handle(contact,kind,id,sdp);
    }
    private void renderCall(CallSession.State state,String contactId,String displayName){
        if(closed)return;
        if(contactId!=null&&!contactId.isEmpty()){callContact=contactId;if(displayName!=null&&!displayName.isBlank())callName=displayName;}
        if(callName==null||callName.isEmpty())callName=nameFor(callContact);
        switch(state){
            case CALLING->showCall("Звоним… "+callName,false,false,true,false);
            case RINGING->showCall("Входящий звонок · "+callName,true,true,false,false);
            case CONNECTED->showCall("В сети · "+callName,false,false,true,false);
            case FAILED->showCall("Не удалось · "+callName,false,false,false,true);
            case ENDED->{callPanel.setVisible(false);callOffer="";callCallId="";callSession=null;status.setText("Звонок завершён");}
            case IDLE->{}
        }
        updateCallButton();callPanel.revalidate();callPanel.repaint();
    }
    private void showCall(String text,boolean accept,boolean reject,boolean end,boolean dismiss){
        callTitle.setText(text);acceptCall.setVisible(accept);rejectCall.setVisible(reject);hangup.setVisible(end);dismissCall.setVisible(dismiss);
        callPanel.setVisible(true);callPanel.revalidate();callPanel.repaint();
    }
    private void acceptIncoming(){
        if(closed)return;
        CallSession session=callSession;
        if(session==null||callOffer.isEmpty()||callCallId.isEmpty())return;
        ChatService current=service;
        if(current==null){status.setText("Чаты ещё не готовы");return;}
        String offer=callOffer,id=callCallId;callOffer="";callCallId="";
        showCall("Соединяем… · "+callName,false,false,true,false);
        session.accept(current,callContact,id,offer);updateCallButton();
    }
    private void rejectIncoming(){
        CallSession session=callSession;
        if(session!=null)session.reject(callContact);
        callSession=null;callOffer="";callCallId="";callPanel.setVisible(false);status.setText("Звонок отклонён");updateCallButton();
    }
    private void hangupCall(){
        CallSession session=callSession;
        if(session!=null)session.hangup();
        callPanel.setVisible(false);updateCallButton();
    }
    private void dismissCallPanel(){
        CallSession session=callSession;callSession=null;if(session!=null)session.close();
        callOffer="";callCallId="";callPanel.setVisible(false);updateCallButton();
    }
    /** Closes a stale (ended or failed) session before a new call is created. */
    private void releaseCall(){
        CallSession session=callSession;callSession=null;if(session!=null)session.close();
        callOffer="";callCallId="";
    }
    private boolean activeCall(){
        CallSession session=callSession;
        if(session==null)return false;
        CallSession.State current=session.state();
        return current==CallSession.State.IDLE||current==CallSession.State.CALLING||current==CallSession.State.RINGING||current==CallSession.State.CONNECTED;
    }
    private void updateCallButton(){
        call.setEnabled(!closed&&!selected.isEmpty()&&service!=null&&!activeCall());
        call.setToolTipText(selected.isEmpty()?"Выберите контакт для звонка":"Позвонить контакту");
    }
    private void selectContact(String id,String displayName){
        if(id.equals(selected))return;
        drafts.put(selected,draft.getText());selected=id;heading.setText(displayName);activeAvatar.setText(initials(displayName));
        draft.setText(drafts.getOrDefault(selected,""));signature="";updateCallButton();showText("Загружаем историю…");revalidate();repaint();refresh();
    }
    private String nameFor(String id){
        for(var contact:cached)if(contact.id().equals(id))return contact.name();
        return "Контакт";
    }
    private void addContact(){JTextField code=new JTextField(),name=new JTextField();style(code);style(name);JPanel form=column();form.add(label("Код приглашения"));form.add(code);form.add(label("Имя контакта"));form.add(name);JTextArea trust=new JTextArea("Сравните код безопасности через независимый доверенный канал до добавления контакта.",3,30);style(trust);trust.setEditable(false);trust.setLineWrap(true);trust.setWrapStyleWord(true);form.add(trust);if(JOptionPane.showConfirmDialog(this,form,"Добавить контакт",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;String codeValue=code.getText().trim(),nameValue=name.getText().trim();work(()->{String safety=ChatInvite.decode(codeValue).safetyCode();postUi(()->{if(JOptionPane.showConfirmDialog(this,"Код безопасности: "+safety+"\nСравните с контактом через независимый доверенный канал.\nКод совпадает?","Проверка контакта",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)work(()->service.addContact(codeValue,nameValue));});});}
    private void invitation(){JTextField host=new JTextField();style(host);host.setText(LocalAddress.preferred());JTextField relay=new JTextField();style(relay);relay.setToolTipText("host:port:pin — печатает узел relay; пусто — только прямое соединение");JPanel form=column();form.add(label("Адрес, доступный вашему контакту"));form.add(host);form.add(label("Relay для разных сетей (необязательно)"));form.add(relay);form.add(label("Локальный порт: "+(service==null?"—":service.localPort())));JTextArea notice=new JTextArea("Передавайте приглашение через доверенный канал. Создавайте свежее приглашение каждому контакту. Не используйте коды повторно. Адрес за NAT не становится автоматически доступным.",4,30);style(notice);notice.setLineWrap(true);notice.setWrapStyleWord(true);notice.setEditable(false);form.add(notice);if(JOptionPane.showConfirmDialog(this,form,"Моё приглашение",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;String advertisedHost=host.getText().trim(),relayValue=relay.getText().trim();work(()->{String code=service.invitation(advertisedHost,relayValue);String safety=ChatInvite.decode(code).safetyCode();postUi(()->{JTextArea value=new JTextArea("Код безопасности: "+safety+"\n\n"+code,6,32);style(value);value.setLineWrap(true);value.setEditable(false);if(JOptionPane.showConfirmDialog(this,scroll(value),"Скопировать приглашение?",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code),null);});});}
    /** Attachment bubble: name and human size, plus save and (for audio) playback actions. */
    private JPanel attachmentBody(ChatRepository.Message m){
        ChatRepository.Attachment a=m.attachment();
        JPanel body=new JPanel();body.setLayout(new BoxLayout(body,BoxLayout.Y_AXIS));body.setOpaque(false);
        JLabel name=new JLabel(shorten(a.name(),40));name.setForeground(TEXT);name.setFont(new Font("Segoe UI",Font.BOLD,13));name.setToolTipText(a.name());name.setAlignmentX(LEFT_ALIGNMENT);body.add(name);
        JLabel size=new JLabel(fileSize(a.size()));size.setForeground(MUTED);size.setFont(new Font("Segoe UI",Font.PLAIN,11));size.setToolTipText(a.mime());size.setAlignmentX(LEFT_ALIGNMENT);body.add(size);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,4));actions.setOpaque(false);actions.setAlignmentX(LEFT_ALIGNMENT);
        JButton save=smallButton("Сохранить");save.setToolTipText("Сохранить файл");save.getAccessibleContext().setAccessibleName("Сохранить файл");save.addActionListener(e->saveAttachment(m));actions.add(save);
        if(a.mime().startsWith("audio/")){JButton play=smallButton("▶");play.setFont(new Font(ICON_FONT,Font.PLAIN,12));play.setToolTipText("Воспроизвести запись");play.getAccessibleContext().setAccessibleName("Воспроизвести запись");play.addActionListener(e->togglePlayback(m.id()));actions.add(play);}
        body.add(actions);return body;
    }
    /** A single explicit user action sends one file; nothing is read or sent in the background. */
    private void chooseFile(){
        if(closed)return;String contact=selected;
        if(contact.isEmpty()){status.setText("Выберите контакт для отправки файла");return;}
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Отправить файл");
        if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        File chosen=chooser.getSelectedFile();if(chosen==null||!chosen.isFile())return;
        Path path=chosen.toPath();work(()->sendFile(contact,path));
    }
    /** Runs on the worker so a large attachment never blocks the UI. */
    private void sendFile(String contact,Path path)throws Exception{
        long length=Files.size(path);
        if(length<1){postUi(()->status.setText("Файл пуст. Отправка отменена"));return;}
        if(length>MAX_FILE){fileTooLarge();return;}
        byte[] data=Files.readAllBytes(path);
        if(data.length<1){postUi(()->status.setText("Файл пуст. Отправка отменена"));return;}
        if(data.length>MAX_FILE){fileTooLarge();return;}
        String name=safeName(path.getFileName().toString());
        service.sendFile(contact,name,mime(path),data);
        postUi(()->status.setText("Файл поставлен в очередь: "+shorten(name,32)));
    }
    private void fileTooLarge(){postUi(()->{status.setText("Файл больше 8 МБ. Отправка отменена");JOptionPane.showMessageDialog(this,"Файл больше 8 МБ. Выберите файл меньшего размера.","Отправка файла",JOptionPane.WARNING_MESSAGE);});}
    /** Trusts the file system only for a lowercase, parameter-free media type. */
    private static String mime(Path path){
        String value=null;try{value=Files.probeContentType(path);}catch(Exception ignored){/* Fallback below. */}
        if(value==null)value="application/octet-stream";
        int parameter=value.indexOf(';');if(parameter>=0)value=value.substring(0,parameter);
        value=value.trim().toLowerCase(Locale.ROOT);
        return value.matches("[a-z0-9.+-]{1,100}/[a-z0-9.+-]{1,100}")?value:"application/octet-stream";
    }
    /** Keeps a local or remote name a single safe file name for chooser suggestions. */
    private static String safeName(String value){
        if(value==null)return "file";
        String base=value.replace('\\','/');int slash=base.lastIndexOf('/');if(slash>=0)base=base.substring(slash+1);
        base=base.replaceAll("[\\p{Cntrl}<>:\"|?*]","_").trim();
        if(base.isEmpty()||base.equals(".")||base.equals(".."))base="file";
        if(base.length()>200)base=base.substring(0,200);
        return base;
    }
    private void toggleVoice(){
        if(closed)return;
        if(recorder!=null){stopVoice();return;}
        String contact=selected;
        if(contact.isEmpty()){status.setText("Выберите контакт для голосового сообщения");return;}
        VoiceRecorder created;
        try{created=new VoiceRecorder((source,wav,error)->postUi(()->voiceFinished(contact,source,wav,error)));created.start();}
        catch(Exception e){status.setText("Микрофон недоступен. Проверьте устройство записи.");return;}
        recorder=created;setRecordingUi(true);status.setText("Запись…");
    }
    private void stopVoice(){VoiceRecorder current=recorder;if(current!=null)current.stop();}
    private void voiceFinished(String contact,VoiceRecorder source,byte[] wav,String error){
        if(recorder!=source)return;
        recorder=null;setRecordingUi(false);
        if(error!=null){status.setText(error);return;}
        if(closed||wav==null||wav.length<=44)return;
        status.setText("Голосовое отправляется…");
        work(()->service.sendFile(contact,"voice-"+System.currentTimeMillis()+".wav","audio/wav",wav));
    }
    private void setRecordingUi(boolean active){
        mic.setText(active?"■":"🎤");mic.setToolTipText(active?"Остановить запись":"Голосовое сообщение");
        mic.getAccessibleContext().setAccessibleName(active?"Остановить запись":"Голосовое сообщение");
    }
    private void saveAttachment(ChatRepository.Message m){
        if(closed||m.attachment()==null)return;
        if(service==null){status.setText("Чаты ещё не готовы");return;}
        JFileChooser chooser=new JFileChooser();chooser.setDialogTitle("Сохранить файл");chooser.setSelectedFile(new File(safeName(m.attachment().name())));
        if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION)return;
        File target=chooser.getSelectedFile();if(target==null)return;
        if(target.exists()&&JOptionPane.showConfirmDialog(this,"Файл уже существует. Перезаписать?\n"+target.getName(),"Сохранить файл",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION)return;
        submit(()->{
            try{
                byte[] data=service.attachment(m.id());if(data==null||data.length==0)throw new java.io.IOException("Missing attachment");
                Files.write(target.toPath(),data);
                postUi(()->status.setText("Файл сохранён: "+target.getName()));
            }catch(Exception e){
                postUi(()->{status.setText("Не удалось сохранить файл");JOptionPane.showMessageDialog(this,"Файл не сохранён. Проверьте папку и доступ к хранилищу.","Файл",JOptionPane.ERROR_MESSAGE);});
            }
        });
    }
    /** "▶" starts playback from decrypted bytes; pressing it again stops the same recording. */
    private void togglePlayback(String messageId){
        if(closed)return;
        if(messageId.equals(playbackId)){stopPlayback();return;}
        stopPlayback();
        if(service==null)return;
        playbackId=messageId;
        submit(()->{
            Clip opened=null;
            try{
                byte[] data=service.attachment(messageId);if(data==null||data.length==0)throw new java.io.IOException("Missing audio");
                try(AudioInputStream stream=AudioSystem.getAudioInputStream(new ByteArrayInputStream(data))){opened=AudioSystem.getClip();opened.open(stream);}
                Clip clip=opened;
                postUi(()->{
                    if(!messageId.equals(playbackId)){closeClip(clip);return;}
                    playbackClip=clip;
                    clip.addLineListener(event->{if(event.getType()==LineEvent.Type.STOP)postUi(()->finishPlayback(messageId,clip));});
                    try{clip.start();}catch(RuntimeException e){finishPlayback(messageId,clip);}
                });
            }catch(Exception e){
                closeClip(opened);
                postUi(()->{if(messageId.equals(playbackId))playbackId="";status.setText("Не удалось воспроизвести запись");});
            }
        });
    }
    private void finishPlayback(String messageId,Clip clip){
        if(messageId.equals(playbackId)&&clip==playbackClip){playbackId="";playbackClip=null;}
        closeClip(clip);
    }
    private void stopPlayback(){playbackId="";Clip clip=playbackClip;playbackClip=null;closeClip(clip);}
    private static void closeClip(Clip clip){if(clip==null)return;try{clip.stop();}catch(RuntimeException ignored){}try{clip.close();}catch(RuntimeException ignored){}}
    private static JButton smallButton(String title){JButton b=button(title);b.setFont(new Font("Segoe UI",Font.PLAIN,11));b.setBorder(new EmptyBorder(6,10,6,10));return b;}
    private static String shorten(String value,int max){if(value==null||value.isBlank())return "Файл";String text=value.trim();return text.length()<=max?text:text.substring(0,max-1)+"…";}
    private static String fileSize(int bytes){if(bytes>=1024*1024)return String.format(Locale.forLanguageTag("ru"),"%.1f МБ",bytes/1048576.0);if(bytes>=1024)return Math.max(1,(bytes+1023)/1024)+" КБ";return bytes+" Б";}
    private void postUi(Runnable action){if(closed)return;SwingUtilities.invokeLater(()->{if(!closed)action.run();});}
    private void submit(Runnable action){if(closed)return;try{worker.submit(action);}catch(RejectedExecutionException ignored){/* Closed panel discards late work. */}}
    private interface Action{void run()throws Exception;}
    private void work(Action action){if(closed)return;submit(()->{try{if(service==null)service=DesktopMessenger.chats(root).get();action.run();postUi(this::refresh);}catch(Exception e){postUi(()->{status.setText("Не удалось выполнить действие");JOptionPane.showMessageDialog(this,"Проверьте приглашение, имя или доступность защищённого хранилища.","Чаты",JOptionPane.ERROR_MESSAGE);});}});}
    private static JPanel column(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setBackground(BG);return p;}
    private static JLabel label(String s){JLabel l=new JLabel(s);l.setForeground(TEXT);return l;}
    private static JButton button(String title){JButton b=new JButton(title){protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);if(!isEnabled())g.setComposite(AlphaComposite.SrcOver.derive(.5f));g.setPaint(new GradientPaint(0,0,"➤".equals(getText())?new Color(0x58F3E9):getBackground().brighter(),0,getHeight(),"➤".equals(getText())?new Color(0x1CC7D2):getBackground()));g.fillRoundRect(0,0,getWidth()-1,getHeight()-2,16,16);g.setColor(hasFocus()?CYAN:new Color(0x36546C));g.drawRoundRect(0,0,getWidth()-1,getHeight()-2,16,16);if("➤".equals(getText())){g.setColor(BG);g.setStroke(new BasicStroke(1.8f));int cx=getWidth()/2,cy=getHeight()/2;java.awt.geom.Path2D path=new java.awt.geom.Path2D.Float();path.moveTo(cx-8,cy-8);path.lineTo(cx+9,cy);path.lineTo(cx-8,cy+8);path.lineTo(cx-4,cy);path.closePath();g.draw(path);g.drawLine(cx-4,cy,cx+8,cy);g.dispose();}else{g.dispose();super.paintComponent(raw);}}};b.setFont(new Font("Segoe UI",Font.PLAIN,14));b.setContentAreaFilled(false);b.setBackground(SURFACE);b.setForeground(CYAN);b.setFocusPainted(true);b.setBorder(new EmptyBorder(11,13,11,13));return b;}
    private static void style(JTextArea c){c.setFont(new Font("Segoe UI",Font.PLAIN,14));c.setBackground(SURFACE);c.setForeground(TEXT);c.setCaretColor(CYAN);c.setBorder(new EmptyBorder(10,10,10,10));}
    private static void style(JTextField c){c.setFont(new Font("Segoe UI",Font.PLAIN,14));c.setBackground(SURFACE);c.setForeground(TEXT);c.setCaretColor(CYAN);c.setBorder(new EmptyBorder(10,10,10,10));}
    private static JScrollPane scroll(Component c){JScrollPane s=new JScrollPane(c);s.setBorder(null);s.getViewport().setBackground(BG);s.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);return s;}
    public Dimension getPreferredScrollableViewportSize(){return new Dimension(480,600);}
    public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}
    public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return 200;}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return true;}
    @Override public void close(){if(closed)return;closed=true;CallSession activeCall=callSession;callSession=null;callOffer="";callCallId="";if(activeCall!=null)activeCall.close();VoiceRecorder active=recorder;recorder=null;if(active!=null)active.stop();stopPlayback();worker.shutdownNow();}
}
