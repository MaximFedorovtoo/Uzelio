package org.uzel.desktop.messenger;

import javax.swing.*;
import javax.swing.event.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
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
    // Ocean 2.0 tokens: docs/design-tokens-2026.md.
    private static final Color VOID=new Color(0x04090F),BG=new Color(0x070E17),BG2=new Color(0x0A1420),SURFACE=new Color(0x0E1E2E),SURFACE2=new Color(0x122639),RAISED=new Color(0x163149),TEXT=new Color(0xEAF6FF),MUTED=new Color(0x93ADC4),MUTED2=new Color(0x6F8BA3),CYAN=new Color(0x38F0DD),ACCENT_DEEP=new Color(0x1CC7D2),MINT=new Color(0x7CF6C8),IRIS=new Color(0x8F7CFF),AMBER=new Color(0xFFC978),CORAL=new Color(0xFF8E9A),LINE=new Color(148,190,222,36),LINE_STRONG=new Color(148,190,222,66);
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
    private final Map<String,Long> lastMessageTime=new HashMap<>();
    private java.util.List<ChatRepository.Contact> cached=java.util.List.of();
    private String selected="",signature="";
    private volatile ChatService service;
    private boolean pending;
    private volatile boolean closed;
    public MessengerPanel(Path root){
        this.root=root;setBackground(BG);setLayout(new BorderLayout());
        JPanel sidebar=new JPanel(new BorderLayout(0,14));sidebar.setBackground(BG2);sidebar.setBorder(new CompoundBorder(new MatteBorder(0,0,0,1,LINE),new EmptyBorder(20,18,16,18)));
        JPanel tools=column();tools.setOpaque(false);JPanel titleRow=new JPanel(new BorderLayout(10,0));titleRow.setOpaque(false);
        JLabel sectionTitle=label("Чаты");sectionTitle.setFont(new Font("Segoe UI",Font.BOLD,26));titleRow.add(sectionTitle,BorderLayout.CENTER);
        JButton add=button("+");add.setPreferredSize(new Dimension(38,38));add.setToolTipText("Добавить контакт");add.getAccessibleContext().setAccessibleName("Добавить контакт");titleRow.add(add,BorderLayout.EAST);tools.add(titleRow);tools.add(Box.createVerticalStrut(16));
        style(search);search.setBackground(SURFACE2);search.setBorder(new RoundedBorder(LINE_STRONG,999,new Insets(9,14,9,14)));search.setToolTipText("Поиск по чатам");search.getAccessibleContext().setAccessibleName("Поиск по чатам");JLabel searchLabel=label("Поиск по чатам");searchLabel.setFont(new Font("Segoe UI",Font.PLAIN,11));searchLabel.setForeground(MUTED2);searchLabel.setAlignmentX(Component.LEFT_ALIGNMENT);searchLabel.setMaximumSize(new Dimension(Integer.MAX_VALUE,16));tools.add(searchLabel);tools.add(search);sidebar.add(tools,BorderLayout.NORTH);sidebar.add(scroll(contacts),BorderLayout.CENTER);
        JPanel footer=column();footer.setOpaque(false);JPanel privacyCard=new SurfacePanel(SURFACE,18);privacyCard.setLayout(new BoxLayout(privacyCard,BoxLayout.Y_AXIS));privacyCard.setBorder(new EmptyBorder(12,14,12,14));JLabel privacyTitle=label("Ваши разговоры — у вас");privacyTitle.setFont(new Font("Segoe UI",Font.BOLD,13));privacyCard.add(privacyTitle);JTextArea privacy=new JTextArea("История хранится только на устройствах участников.",3,20);style(privacy);privacy.setOpaque(false);privacy.setEditable(false);privacy.setLineWrap(true);privacy.setWrapStyleWord(true);privacy.setFont(new Font("Segoe UI",Font.PLAIN,11));privacy.setForeground(MUTED);privacyCard.add(privacy);footer.add(privacyCard);footer.add(Box.createVerticalStrut(10));JButton invite=button("Моё приглашение");footer.add(invite);status.setFont(new Font("Segoe UI",Font.PLAIN,11));status.setForeground(MUTED2);footer.add(status);retry.setVisible(false);retry.addActionListener(e->{if(service==null)refresh();else work(()->service.retryNow());});footer.add(retry);sidebar.add(footer,BorderLayout.SOUTH);
        JPanel list=sidebar;
        JPanel center=new JPanel(null){public void doLayout(){int w=getWidth(),h=getHeight();boolean wide=w>700;back.setVisible(!wide);list.setVisible(wide||selected.isEmpty());conversation.setVisible(wide||!selected.isEmpty());if(wide){list.setBounds(0,0,320,h);conversation.setBounds(320,0,Math.max(0,w-320),h);}else{list.setBounds(0,0,w,h);conversation.setBounds(0,0,w,h);}}};center.setOpaque(false);center.add(list);center.add(conversation);add(center,BorderLayout.CENTER);
        conversation.setBackground(BG);conversation.setBorder(new EmptyBorder(16,24,14,24));
        JPanel title=column();JPanel head=new JPanel(new BorderLayout(10,0));head.setOpaque(false);back.setPreferredSize(new Dimension(36,36));back.setToolTipText("Назад к чатам");back.addActionListener(e->{drafts.put(selected,draft.getText());selected="";updateCallButton();revalidate();repaint();});head.add(back,BorderLayout.WEST);heading.setForeground(TEXT);heading.setFont(new Font("Segoe UI",Font.BOLD,19));JPanel person=new JPanel(new BorderLayout(12,0));person.setOpaque(false);activeAvatar.setPreferredSize(new Dimension(42,42));person.add(activeAvatar,BorderLayout.WEST);person.add(heading,BorderLayout.CENTER);head.add(person,BorderLayout.CENTER);
        call.setEnabled(false);call.setToolTipText("Позвонить контакту");call.getAccessibleContext().setAccessibleName("Позвонить контакту");call.addActionListener(e->startCall());head.add(call,BorderLayout.EAST);title.add(head);
        JLabel route=new JLabel("Личная переписка · сквозное шифрование",SwingConstants.CENTER);route.setForeground(MUTED2);route.setFont(new Font("Segoe UI",Font.PLAIN,11));route.setBorder(new EmptyBorder(14,0,14,0));route.setAlignmentX(Component.CENTER_ALIGNMENT);route.setMaximumSize(new Dimension(Integer.MAX_VALUE,44));title.add(route);
        callTitle.setForeground(TEXT);callTitle.setFont(new Font("Segoe UI",Font.PLAIN,12));callPanel.setOpaque(false);callPanel.setBorder(new EmptyBorder(0,0,10,0));callPanel.setVisible(false);callPanel.add(callTitle);callActions.setOpaque(false);
        acceptCall.setToolTipText("Принять звонок");acceptCall.getAccessibleContext().setAccessibleName("Принять звонок");acceptCall.addActionListener(e->acceptIncoming());
        rejectCall.setToolTipText("Отклонить звонок");rejectCall.getAccessibleContext().setAccessibleName("Отклонить звонок");rejectCall.addActionListener(e->rejectIncoming());
        hangup.setToolTipText("Завершить звонок");hangup.getAccessibleContext().setAccessibleName("Завершить звонок");hangup.addActionListener(e->hangupCall());
        dismissCall.setToolTipText("Скрыть сообщение о звонке");dismissCall.getAccessibleContext().setAccessibleName("Скрыть сообщение о звонке");dismissCall.addActionListener(e->dismissCallPanel());
        callActions.add(acceptCall);callActions.add(rejectCall);callActions.add(hangup);callActions.add(dismissCall);callPanel.add(callActions);title.add(callPanel);
        conversation.add(title,BorderLayout.NORTH);messages.setBorder(new EmptyBorder(8,4,12,4));conversation.add(scroll(messages),BorderLayout.CENTER);
        JPanel composer=new JPanel(new BorderLayout(10,7));composer.setOpaque(false);composer.setBorder(new EmptyBorder(12,4,0,4));draft.setRows(1);draft.setLineWrap(true);draft.setWrapStyleWord(true);style(draft);draft.setOpaque(false);draft.setBackground(SURFACE2);draft.setBorder(new EmptyBorder(8,10,8,10));draft.getAccessibleContext().setAccessibleName("Сообщение");JScrollPane input=scroll(draft);input.setOpaque(false);input.getViewport().setOpaque(false);input.setBorder(null);input.setPreferredSize(new Dimension(100,42));SurfacePanel shell=new SurfacePanel(SURFACE2,22);shell.setLayout(new BorderLayout(6,0));shell.setBorder(new EmptyBorder(2,8,2,4));shell.add(input,BorderLayout.CENTER);send.setPreferredSize(new Dimension(44,44));send.setText("➤");send.setToolTipText("Отправить");send.getAccessibleContext().setAccessibleName("Отправить");
        attach.setPreferredSize(new Dimension(40,40));attach.setBorder(new EmptyBorder(8,8,8,8));attach.setFont(new Font(ICON_FONT,Font.PLAIN,17));attach.setToolTipText("Отправить файл");attach.getAccessibleContext().setAccessibleName("Отправить файл");attach.addActionListener(e->chooseFile());
        mic.setPreferredSize(new Dimension(40,40));mic.setBorder(new EmptyBorder(8,8,8,8));mic.setFont(new Font(ICON_FONT,Font.PLAIN,17));mic.setToolTipText("Голосовое сообщение");mic.getAccessibleContext().setAccessibleName("Голосовое сообщение");mic.addActionListener(e->toggleVoice());
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,2,0));actions.setOpaque(false);actions.add(attach);actions.add(mic);actions.add(send);shell.add(actions,BorderLayout.EAST);composer.add(shell,BorderLayout.CENTER);JLabel hint=new JLabel("Сообщение дождётся соединения ваших устройств",SwingConstants.CENTER);hint.setFont(new Font("Segoe UI",Font.PLAIN,11));hint.setForeground(MUTED2);composer.add(hint,BorderLayout.SOUTH);conversation.add(composer,BorderLayout.SOUTH);send.setEnabled(false);send.addActionListener(e->send());
        search.getDocument().addDocumentListener(change(this::renderContacts));draft.getDocument().addDocumentListener(change(()->{drafts.put(selected,draft.getText());send.setEnabled(service!=null&&!selected.isEmpty()&&!draft.getText().trim().isEmpty()&&!pending);}));add.addActionListener(e->addContact());invite.addActionListener(e->invitation());renderContacts();showText("Выберите контакт для переписки");
    }
    private static DocumentListener change(Runnable action){return new DocumentListener(){public void insertUpdate(DocumentEvent e){action.run();}public void removeUpdate(DocumentEvent e){action.run();}public void changedUpdate(DocumentEvent e){action.run();}};}
    public void refresh(){if(pending||closed)return;pending=true;String id=selected;submit(()->{try{if(service==null)service=DesktopMessenger.chats(root).get();var list=service.contacts();var history=id.isEmpty()?java.util.List.<ChatRepository.Message>of():service.history(id);postUi(()->{if(closed)return;pending=false;cached=list;for(var message:history)lastMessageTime.put(message.contactId(),message.timeMillis());retry.setVisible(false);status.setText(recorder!=null?"Запись…":"История остаётся на устройствах участников");renderContacts();if(selected.equals(id))renderHistory(history);send.setEnabled(!selected.isEmpty()&&!draft.getText().trim().isEmpty());updateCallButton();});}catch(Exception e){postUi(()->{pending=false;status.setText("Чаты недоступны. Повторите после разблокировки устройства.");retry.setVisible(true);});}});}
    private void renderContacts(){contacts.setBackground(BG2);contacts.removeAll();String q=search.getText().trim().toLowerCase(Locale.ROOT);int count=0;for(var c:cached){if(!c.name().toLowerCase(Locale.ROOT).contains(q))continue;count++;
        Color tint=avatarTint(c.name());if(c.id().equals(selected))((AvatarLabel)activeAvatar).tint(tint);
        JButton row=button("");row.setLayout(new BorderLayout(12,0));row.setAlignmentX(LEFT_ALIGNMENT);row.setMaximumSize(new Dimension(Integer.MAX_VALUE,76));row.setPreferredSize(new Dimension(260,76));row.setBackground(c.id().equals(selected)?RAISED:SURFACE);row.setBorder(new EmptyBorder(8,10,8,12));
        AvatarLabel avatar=new AvatarLabel(initials(c.name()));avatar.tint(tint);avatar.setHorizontalAlignment(SwingConstants.CENTER);avatar.setFont(new Font("Segoe UI",Font.BOLD,16));avatar.setOpaque(false);avatar.setPreferredSize(new Dimension(44,44));row.add(avatar,BorderLayout.WEST);JPanel copy=column();copy.setOpaque(false);JLabel name=label(c.name());name.setFont(new Font("Segoe UI",Font.BOLD,15));copy.add(name);JLabel hint=label("Личная переписка");hint.setFont(new Font("Segoe UI",Font.PLAIN,12));hint.setForeground(MUTED);copy.add(hint);row.add(copy,BorderLayout.CENTER);JPanel meta=column();meta.setOpaque(false);meta.setBorder(new EmptyBorder(4,0,0,4));Long time=lastMessageTime.get(c.id());if(time!=null){JLabel stamp=label(timeText(time));stamp.setFont(new Font("Segoe UI",Font.PLAIN,11));stamp.setForeground(MUTED2);stamp.setHorizontalAlignment(SwingConstants.RIGHT);meta.add(stamp);}row.add(meta,BorderLayout.EAST);row.getAccessibleContext().setAccessibleName(c.name());
        row.addActionListener(e->{drafts.put(selected,draft.getText());selected=c.id();heading.setText(c.name());activeAvatar.setText(initials(c.name()));((AvatarLabel)activeAvatar).tint(avatarTint(c.name()));draft.setText(drafts.getOrDefault(selected,""));signature="";updateCallButton();showText("Загружаем историю…");revalidate();repaint();refresh();});contacts.add(row);contacts.add(Box.createVerticalStrut(6));}
        if(count==0){JTextArea empty=new JTextArea(q.isEmpty()?"Пока нет чатов. Добавьте контакт по приглашению.":"Контакты не найдены. Попробуйте другое имя.");style(empty);empty.setOpaque(false);empty.setForeground(MUTED);empty.setFont(new Font("Segoe UI",Font.PLAIN,14));empty.setEditable(false);empty.setLineWrap(true);empty.setWrapStyleWord(true);contacts.add(Box.createVerticalStrut(8));contacts.add(empty);}contacts.revalidate();contacts.repaint();}
    private static String initials(String name){StringBuilder b=new StringBuilder();for(String part:name.trim().split("\\s+")){if(!part.isEmpty())b.appendCodePoint(part.codePointAt(0));if(b.codePointCount(0,b.length())==2)break;}return b.toString().toUpperCase(Locale.ROOT);}
    private static Color avatarTint(String name){Color[] palette={CYAN,MINT,IRIS,AMBER};return palette[Math.floorMod(name==null?0:name.hashCode(),palette.length)];}
    private static String timeText(long millis){Calendar now=Calendar.getInstance(),then=Calendar.getInstance();then.setTimeInMillis(millis);boolean today=now.get(Calendar.YEAR)==then.get(Calendar.YEAR)&&now.get(Calendar.DAY_OF_YEAR)==then.get(Calendar.DAY_OF_YEAR);return new SimpleDateFormat(today?"HH:mm":"dd.MM",Locale.forLanguageTag("ru")).format(new Date(millis));}
    private void renderHistory(java.util.List<ChatRepository.Message> history){
        String next=history.toString();if(next.equals(signature))return;signature=next;messages.removeAll();
        if(history.isEmpty())showText(selected.isEmpty()?"Выберите контакт для переписки":"Сообщений пока нет");
        else for(var m:history){
            JPanel row=new JPanel(new FlowLayout(m.outgoing()?FlowLayout.RIGHT:FlowLayout.LEFT,0,6));row.setOpaque(false);
            JPanel bubble=new BubblePanel(m.outgoing());bubble.setLayout(new BorderLayout(0,6));bubble.setBorder(new EmptyBorder(11,15,9,15));
            if(m.attachment()!=null)bubble.add(attachmentBody(m),BorderLayout.CENTER);
            else {JTextArea body=new JTextArea(m.text());style(body);body.setOpaque(false);body.setEditable(false);body.setBorder(null);body.setLineWrap(true);body.setWrapStyleWord(true);body.setFont(new Font("Segoe UI",Font.PLAIN,14));body.setForeground(TEXT);body.setColumns(28);body.setSize(new Dimension(300,Integer.MAX_VALUE));body.setPreferredSize(new Dimension(300,body.getPreferredSize().height));bubble.add(body,BorderLayout.CENTER);}
            JPanel meta=new JPanel(new FlowLayout(FlowLayout.RIGHT,4,0));meta.setOpaque(false);JLabel stamp=new JLabel(timeText(m.timeMillis()));stamp.setFont(new Font("Segoe UI",Font.PLAIN,11));stamp.setForeground(MUTED2);meta.add(stamp);
            if(m.outgoing()){String status=state(m.status());JLabel delivery=new JLabel(("delivered".equals(m.status())?"✓ ":"")+status);delivery.setFont(new Font("Segoe UI",Font.PLAIN,11));delivery.setForeground("delivered".equals(m.status())?MINT:"queued".equals(m.status())?AMBER:MUTED);meta.add(delivery);}bubble.add(meta,BorderLayout.SOUTH);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE,bubble.getPreferredSize().height+12));row.add(bubble);messages.add(row);
        }
        messages.add(Box.createVerticalGlue());messages.revalidate();messages.repaint();
    }
    private static Shape bubbleShape(double width,double height,boolean outgoing){
        double radius=16,small=6,tl=radius,tr=radius,br=radius,bl=radius;
        if(outgoing)br=small;else bl=small;
        Path2D path=new Path2D.Double();
        path.moveTo(tl,0);path.lineTo(width-tr,0);path.quadTo(width,0,width,tr);path.lineTo(width,height-br);path.quadTo(width,height,width-br,height);path.lineTo(bl,height);path.quadTo(0,height,0,height-bl);path.lineTo(0,tl);path.quadTo(0,0,tl,0);path.closePath();return path;
    }
    private static final class BubblePanel extends JPanel {
        private final boolean outgoing;
        BubblePanel(boolean outgoing){this.outgoing=outgoing;setOpaque(false);}
        protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int width=getWidth(),height=getHeight();if(width>1&&height>1){Shape shape=bubbleShape(width-1,height-1,outgoing);g.setColor(new Color(0,0,0,55));g.translate(0,2);g.fill(shape);g.translate(0,-2);g.setColor(outgoing?new Color(56,240,221,42):SURFACE);g.fill(shape);g.setColor(outgoing?new Color(56,240,221,120):LINE);g.setStroke(new BasicStroke(1f));g.draw(shape);}g.dispose();super.paintComponent(raw);}
    }
    private static final class AvatarLabel extends JLabel {private Color tint=CYAN;AvatarLabel(String value){super(value,SwingConstants.CENTER);setForeground(VOID);setFont(new Font("Segoe UI",Font.BOLD,16));setOpaque(false);}void tint(Color value){tint=value;repaint();}protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int width=getWidth(),height=getHeight();if(width>0&&height>0){g.setPaint(new GradientPaint(0,0,tint.brighter(),width,height,tint));g.fillRoundRect(0,0,width,height,14,14);g.setColor(new Color(255,255,255,28));g.drawRoundRect(0,0,width-1,height-1,14,14);}g.dispose();super.paintComponent(raw);}}
    private static final class BubbleArea extends JTextArea {BubbleArea(String value){super(value);setOpaque(false);}protected void paintComponent(Graphics raw){super.paintComponent(raw);}}
    private static String state(String s){return switch(s){case "delivered"->"Доставлено";case "sent"->"Отправлено";default->"Ожидает отправки";};}
    private void showText(String value){messages.removeAll();JLabel t=new JLabel(value,SwingConstants.CENTER);t.setForeground(MUTED);t.setFont(new Font("Segoe UI",Font.PLAIN,14));t.setMaximumSize(new Dimension(Integer.MAX_VALUE,t.getPreferredSize().height));messages.add(Box.createVerticalStrut(28));messages.add(t);messages.revalidate();messages.repaint();}
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
        drafts.put(selected,draft.getText());selected=id;heading.setText(displayName);activeAvatar.setText(initials(displayName));((AvatarLabel)activeAvatar).tint(avatarTint(displayName));
        draft.setText(drafts.getOrDefault(selected,""));signature="";updateCallButton();showText("Загружаем историю…");revalidate();repaint();refresh();
    }
    private String nameFor(String id){
        for(var contact:cached)if(contact.id().equals(id))return contact.name();
        return "Контакт";
    }
    private void addContact(){JTextField code=new JTextField(),name=new JTextField();style(code);style(name);JPanel form=column();form.add(label("Код приглашения"));form.add(code);form.add(label("Имя контакта"));form.add(name);JTextArea trust=new JTextArea("Сравните код безопасности через независимый доверенный канал до добавления контакта.",3,30);style(trust);trust.setEditable(false);trust.setLineWrap(true);trust.setWrapStyleWord(true);form.add(trust);if(JOptionPane.showConfirmDialog(this,form,"Добавить контакт",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;String codeValue=code.getText().trim(),nameValue=name.getText().trim();work(()->{String safety=ChatInvite.decode(codeValue).safetyCode();postUi(()->{if(JOptionPane.showConfirmDialog(this,"Код безопасности: "+safety+"\nСравните с контактом через независимый доверенный канал.\nКод совпадает?","Проверка контакта",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)work(()->service.addContact(codeValue,nameValue));});});}
    private void invitation(){JTextField host=new JTextField();style(host);host.setText(LocalAddress.preferred());JTextField relay=new JTextField();style(relay);relay.setToolTipText("host:port:pin — печатает узел relay; пусто — только прямое соединение");JPanel form=column();form.add(label("Адрес, доступный вашему контакту"));form.add(host);form.add(label("Relay для разных сетей (необязательно)"));form.add(relay);form.add(label("Локальный порт: "+(service==null?"—":service.localPort())));JTextArea notice=new JTextArea("Передавайте приглашение через доверенный канал. Создавайте свежее приглашение каждому контакту. Не используйте коды повторно. Адрес за NAT не становится автоматически доступным.",4,30);style(notice);notice.setLineWrap(true);notice.setWrapStyleWord(true);notice.setEditable(false);form.add(notice);if(JOptionPane.showConfirmDialog(this,form,"Моё приглашение",JOptionPane.OK_CANCEL_OPTION)!=JOptionPane.OK_OPTION)return;String advertisedHost=host.getText().trim(),relayValue=relay.getText().trim();work(()->{String code=service.invitation(advertisedHost,relayValue);String safety=ChatInvite.decode(code).safetyCode();postUi(()->{JTextArea value=new JTextArea("Код безопасности: "+safety+"\n\n"+code,6,32);style(value);value.setLineWrap(true);value.setEditable(false);if(JOptionPane.showConfirmDialog(this,scroll(value),"Скопировать приглашение?",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION)Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code),null);});});}
    /** Attachment card: type badge, name and human size, plus save and (for audio) playback actions. */
    private JPanel attachmentBody(ChatRepository.Message m){
        ChatRepository.Attachment a=m.attachment();
        SurfacePanel card=new SurfacePanel(SURFACE2,16);card.setLayout(new BorderLayout(10,4));card.setBorder(new EmptyBorder(9,10,9,10));
        FileBadge badge=new FileBadge(extension(a.name()),attachmentTint(a.mime()));badge.setPreferredSize(new Dimension(42,42));card.add(badge,BorderLayout.WEST);
        JPanel info=column();info.setOpaque(false);JLabel name=new JLabel(shorten(a.name(),40));name.setForeground(TEXT);name.setFont(new Font("Segoe UI",Font.BOLD,13));name.setToolTipText(a.name());info.add(name);JLabel size=new JLabel(fileSize(a.size()));size.setForeground(MUTED2);size.setFont(new Font("Segoe UI",Font.PLAIN,11));size.setToolTipText(a.mime());info.add(size);card.add(info,BorderLayout.CENTER);
        JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));actions.setOpaque(false);
        JButton save=smallButton("Сохранить");save.setToolTipText("Сохранить файл");save.getAccessibleContext().setAccessibleName("Сохранить файл");save.addActionListener(e->saveAttachment(m));actions.add(save);
        if(a.mime().startsWith("audio/")){JButton play=smallButton("▶");play.setFont(new Font(ICON_FONT,Font.PLAIN,12));play.setToolTipText("Воспроизвести запись");play.getAccessibleContext().setAccessibleName("Воспроизвести запись");play.addActionListener(e->togglePlayback(m.id()));actions.add(play);}
        card.add(actions,BorderLayout.SOUTH);return card;
    }
    private static String extension(String name){if(name==null)return "FILE";int dot=name.lastIndexOf('.');String value=dot>=0&&dot<name.length()-1?name.substring(dot+1):"FILE";value=value.replaceAll("[^A-Za-z0-9]","").toUpperCase(Locale.ROOT);if(value.isEmpty())value="FILE";return value.length()>4?value.substring(0,4):value;}
    private static Color attachmentTint(String mime){if(mime==null)return CYAN;if(mime.startsWith("audio/"))return MINT;if(mime.startsWith("image/"))return IRIS;if(mime.startsWith("video/"))return AMBER;if(mime.contains("pdf"))return AMBER;return CYAN;}
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
    private static JButton smallButton(String title){JButton b=button(title);b.setFont(new Font("Segoe UI",Font.BOLD,11));b.setBorder(new EmptyBorder(6,10,6,10));return b;}
    private static String shorten(String value,int max){if(value==null||value.isBlank())return "Файл";String text=value.trim();return text.length()<=max?text:text.substring(0,max-1)+"…";}
    private static String fileSize(int bytes){if(bytes>=1024*1024)return String.format(Locale.forLanguageTag("ru"),"%.1f МБ",bytes/1048576.0);if(bytes>=1024)return Math.max(1,(bytes+1023)/1024)+" КБ";return bytes+" Б";}
    private void postUi(Runnable action){if(closed)return;SwingUtilities.invokeLater(()->{if(!closed)action.run();});}
    private void submit(Runnable action){if(closed)return;try{worker.submit(action);}catch(RejectedExecutionException ignored){/* Closed panel discards late work. */}}
    private interface Action{void run()throws Exception;}
    private void work(Action action){if(closed)return;submit(()->{try{if(service==null)service=DesktopMessenger.chats(root).get();action.run();postUi(this::refresh);}catch(Exception e){postUi(()->{status.setText("Не удалось выполнить действие");JOptionPane.showMessageDialog(this,"Проверьте приглашение, имя или доступность защищённого хранилища.","Чаты",JOptionPane.ERROR_MESSAGE);});}});}
    private static JPanel column(){JPanel p=new JPanel();p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setBackground(BG);return p;}
    private static JLabel label(String s){JLabel l=new JLabel(s);l.setForeground(TEXT);return l;}
    private static JButton button(String title){JButton b=new JButton(title){private boolean hovered;{addMouseListener(new MouseAdapter(){public void mouseEntered(MouseEvent e){hovered=true;repaint();}public void mouseExited(MouseEvent e){hovered=false;repaint();}});}protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int w=getWidth(),h=getHeight();String text=getText();boolean send="➤".equals(text),plus="+".equals(text),row=text==null||text.isEmpty(),icon="📎".equals(text)||"🎤".equals(text)||"■".equals(text)||"▶".equals(text);if(!isEnabled())g.setComposite(AlphaComposite.SrcOver.derive(.55f));if(send){int d=Math.max(1,Math.min(w,h)),cx=w/2,cy=h/2;if(isEnabled()){g.setPaint(new GradientPaint(0,0,CYAN,0,d,ACCENT_DEEP));g.fillOval((w-d)/2,(h-d)/2,d,d);g.setColor(new Color(124,246,200,120));g.drawOval((w-d)/2,(h-d)/2,d-1,d-1);g.setColor(VOID);}else{g.setColor(SURFACE2);g.fillOval((w-d)/2,(h-d)/2,d,d);g.setColor(LINE);g.drawOval((w-d)/2,(h-d)/2,d-1,d-1);g.setColor(MUTED2);}g.setStroke(new BasicStroke(1.8f));int offset=Math.max(6,d/2-9);Path2D arrow=new Path2D.Float();arrow.moveTo(cx-offset,cy-offset);arrow.lineTo(cx+offset+1,cy);arrow.lineTo(cx-offset,cy+offset);arrow.lineTo(cx-offset+4,cy);arrow.closePath();g.draw(arrow);g.drawLine(cx-offset+4,cy,cx+offset,cy);}else if(icon){int d=Math.max(1,Math.min(w,h));if(hovered&&isEnabled()){g.setColor(new Color(148,190,222,22));g.fillOval((w-d)/2,(h-d)/2,d,d);}g.setColor(isEnabled()?TEXT:MUTED2);int cx=w/2,cy=h/2;if("🎤".equals(text)){g.setStroke(new BasicStroke(1.7f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.drawRoundRect(cx-3,cy-10,6,12,6,6);g.drawArc(cx-8,cy-5,16,14,200,140);g.drawLine(cx,cy+9,cx,cy+13);g.drawLine(cx-4,cy+13,cx+4,cy+13);}else if("■".equals(text)){g.fillRoundRect(cx-5,cy-5,10,10,3,3);}else if("▶".equals(text)){Path2D play=new Path2D.Float();play.moveTo(cx-4,cy-6);play.lineTo(cx+6,cy);play.lineTo(cx-4,cy+6);play.closePath();g.fill(play);}else{g.setFont(new Font(ICON_FONT,Font.PLAIN,17));FontMetrics fm=g.getFontMetrics();g.drawString(text,cx-fm.stringWidth(text)/2,(h+fm.getAscent()-fm.getDescent())/2);}}else if(plus){int d=Math.max(1,Math.min(w,h));g.setColor(hovered?RAISED:SURFACE2);g.fillOval((w-d)/2,(h-d)/2,d,d);g.setColor(LINE_STRONG);g.drawOval((w-d)/2,(h-d)/2,d-1,d-1);g.setColor(CYAN);g.setStroke(new BasicStroke(1.8f));int arm=6;g.drawLine(w/2-arm,h/2,w/2+arm,h/2);g.drawLine(w/2,h/2-arm,w/2,h/2+arm);}else if(row){int radius=16;Color base=getBackground();if(hovered)base=base.brighter();g.setColor(base);g.fillRoundRect(0,0,w-1,h-1,radius,radius);g.setColor(LINE);g.drawRoundRect(0,0,w-1,h-1,radius,radius);}else{int radius=h/2;g.setColor(new Color(0,0,0,45));g.fillRoundRect(1,3,w,h,radius,radius);g.setPaint(new GradientPaint(0,0,hovered?RAISED:SURFACE2,0,h,new Color(10,20,32)));g.fillRoundRect(0,0,w,h-2,radius,radius);g.setColor(hasFocus()?CYAN:LINE_STRONG);g.drawRoundRect(0,0,w-1,h-3,radius,radius);}g.dispose();if(!send&&!icon&&!plus)super.paintComponent(raw);}};b.setFont(new Font("Segoe UI",Font.BOLD,14));b.setContentAreaFilled(false);b.setBackground(SURFACE);b.setForeground(TEXT);b.setFocusPainted(false);b.setBorder(new EmptyBorder(9,13,9,13));return b;}
    private static void style(JTextArea c){c.setFont(new Font("Segoe UI",Font.PLAIN,14));c.setBackground(SURFACE);c.setForeground(TEXT);c.setCaretColor(CYAN);c.setBorder(new EmptyBorder(10,10,10,10));}
    private static void style(JTextField c){c.setFont(new Font("Segoe UI",Font.PLAIN,14));c.setBackground(SURFACE);c.setForeground(TEXT);c.setCaretColor(CYAN);c.setBorder(new EmptyBorder(10,10,10,10));}
    private static JScrollPane scroll(Component c){JScrollPane s=new JScrollPane(c);s.setBorder(null);s.setOpaque(false);s.getViewport().setOpaque(false);s.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);JScrollBar bar=s.getVerticalScrollBar();bar.setPreferredSize(new Dimension(5,0));bar.setUnitIncrement(24);bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI(){protected JButton createDecreaseButton(int orientation){JButton b=new JButton();b.setPreferredSize(new Dimension());return b;}protected JButton createIncreaseButton(int orientation){return createDecreaseButton(orientation);}protected void paintTrack(Graphics g,JComponent c,Rectangle bounds){g.setColor(BG);g.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);}protected void paintThumb(Graphics g,JComponent c,Rectangle bounds){Graphics2D g2=(Graphics2D)g;g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g2.setColor(RAISED);g2.fillRoundRect(bounds.x,bounds.y+1,bounds.width,Math.max(8,bounds.height-2),bounds.width,bounds.width);}});return s;}
    private static final class RoundedBorder extends AbstractBorder {
        private final Color color;private final int radius;private final Insets insets;
        RoundedBorder(Color color,int radius,Insets insets){this.color=color;this.radius=radius;this.insets=insets;}
        public Insets getBorderInsets(Component c){return insets;}
        public Insets getBorderInsets(Component c,Insets target){target.set(insets.top,insets.left,insets.bottom,insets.right);return target;}
        public void paintBorder(Component c,Graphics raw,int x,int y,int w,int h){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int arc=Math.min(radius,Math.min(w,h));g.setColor(color);g.drawRoundRect(x,y,w-1,h-1,arc,arc);g.dispose();}
    }
    private static final class SurfacePanel extends JPanel {
        private final Color fill;private final int radius;
        SurfacePanel(Color fill,int radius){this.fill=fill;this.radius=radius;setOpaque(false);}
        protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int w=getWidth(),h=getHeight();if(w>0&&h>0){int arc=Math.min(radius,Math.min(w,h));g.setColor(fill);g.fillRoundRect(0,0,w-1,h-1,arc,arc);g.setColor(LINE);g.drawRoundRect(0,0,w-1,h-1,arc,arc);}g.dispose();super.paintComponent(raw);}
    }
    private static final class FileBadge extends JLabel {
        private final Color tint;
        FileBadge(String value,Color tint){super(value,SwingConstants.CENTER);this.tint=tint;setForeground(tint);setFont(new Font("Segoe UI",Font.BOLD,10));setOpaque(false);}
        protected void paintComponent(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);int w=getWidth(),h=getHeight();if(w>0&&h>0){g.setColor(new Color(tint.getRed(),tint.getGreen(),tint.getBlue(),38));g.fillRoundRect(0,0,w,h,14,14);g.setColor(new Color(tint.getRed(),tint.getGreen(),tint.getBlue(),96));g.drawRoundRect(0,0,w-1,h-1,14,14);}g.dispose();super.paintComponent(raw);}
    }
    public Dimension getPreferredScrollableViewportSize(){return new Dimension(480,600);}
    public int getScrollableUnitIncrement(Rectangle r,int orientation,int direction){return 24;}
    public int getScrollableBlockIncrement(Rectangle r,int orientation,int direction){return 200;}
    public boolean getScrollableTracksViewportWidth(){return true;}
    public boolean getScrollableTracksViewportHeight(){return true;}
    @Override public void close(){if(closed)return;closed=true;CallSession activeCall=callSession;callSession=null;callOffer="";callCallId="";if(activeCall!=null)activeCall.close();VoiceRecorder active=recorder;recorder=null;if(active!=null)active.stop();stopPlayback();worker.shutdownNow();}
}
