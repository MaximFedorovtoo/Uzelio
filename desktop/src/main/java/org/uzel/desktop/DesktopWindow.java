package org.uzel.desktop;

import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.StringSelection;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Android composition rendered with the same original vector artwork and offline geography. */
final class DesktopWindow extends JPanel {
    private static final Color BG=DesktopArtwork.BG,CYAN=DesktopArtwork.CYAN,TEXT=DesktopArtwork.WHITE,MUTED=DesktopArtwork.MUTED;
    private final Path root;
    private final DesktopBackend backend;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-desktop");t.setDaemon(true);return t;});
    private final ExecutorService connectionWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-connection");t.setDaemon(true);return t;});
    private final ExecutorService connectionControl=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-connection-control");t.setDaemon(true);return t;});
    private Future<?> connectionTask;
    private long connectionRequest,connectionRevision;
    private boolean connectionPending,connectionCancelled;
    private String connectionFailure="",connectionPeer="";
    private final Page page=new Page();
    private final JPanel header=new JPanel(null){public void doLayout(){for(Component c:getComponents())c.setBounds((tab==0||tab==4)?getWidth()-77:12,12,58,54);}protected void paintComponent(Graphics raw){Graphics2D g=quality(raw);g.setColor(BG);g.fillRect(0,0,getWidth(),getHeight());if(tab==0||tab==4){text(g,"Uzel",24,48,32,TEXT,true);FontMetrics fm=g.getFontMetrics();text(g,"io",24+fm.stringWidth("Uzel"),48,32,CYAN,true);DesktopArtwork.paint(g,"settings",getWidth()-67,18,40,TEXT);}else{DesktopArtwork.paint(g,"back",19,24,30,TEXT);text(g,new String[]{"","Карта сети","Трафик","Профиль","Чаты"}[tab],64,47,27,TEXT,true);}g.dispose();}};
    private final JPanel nav=new JPanel(new GridLayout(1,3));
    private final JScrollPane scroll=new JScrollPane(page);
    private org.uzel.desktop.messenger.MessengerPanel messengerPanel;
    private DesktopBackend.Snapshot state;
    private DesktopTrafficService.Snapshot traffic;
    private int selectedCount;
    private DesktopRoutingMode routingMode=DesktopRoutingMode.SELECTED_APPLICATIONS;
    private WindowsAppRouter.Availability routingAvailability=new WindowsAppRouter.Availability(false,false,"");
    private WindowsAppRouter.Snapshot routingState=new WindowsAppRouter.Snapshot(false,"OFF","");
    private boolean routingObserved;
    private final ExecutorService iconWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-app-icons");t.setDaemon(true);return t;});
    private final Map<Path,Icon> applicationIcons=new ConcurrentHashMap<>();
    private final Set<String> connectable=new HashSet<>();
    private final Set<String> routedDevices=new HashSet<>();
    private DesktopBackend.DiscoveryStatus discoveryStatus=new DesktopBackend.DiscoveryStatus("NOT_STARTED","",0);
    private final ExecutorService discoveryWorker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-discovery-query");t.setDaemon(true);return t;});
    private final ExecutorService observer=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-status");t.setDaemon(true);return t;});
    private java.util.List<DeviceDirectoryView.Device> devices=java.util.List.of();
    private int tab,region,visited,chipOffset;
    private boolean busy,searching,searched,refreshPending,previewRendering,searchPartial=true;
    private String discoveryError="";
    private String error="";
    private JFrame frame;
    private TrayIcon tray;
    private final javax.swing.Timer timer;
    DesktopWindow(Path root,DesktopBackend backend) {
        org.uzel.desktop.messenger.DesktopMessenger.useChatStreams(backend::openChatStream);
        this.root=root;this.backend=backend;state=backend.snapshot();traffic=backend.traffic();
        setLayout(new BorderLayout());setBackground(BG);header.setPreferredSize(new Dimension(520,78));add(header,BorderLayout.NORTH);
        JButton headerAction=transparentButton();headerAction.getAccessibleContext().setAccessibleName("Настройки или назад");headerAction.addActionListener(e->showTab((tab==0||tab==4)?3:0));header.add(headerAction);
        scroll.setBorder(null);scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);scroll.getViewport().setBackground(BG);scroll.getVerticalScrollBar().setPreferredSize(new Dimension(5,0));scroll.getVerticalScrollBar().setUnitIncrement(22);scroll.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI(){protected JButton createDecreaseButton(int orientation){JButton b=new JButton();b.setPreferredSize(new Dimension());return b;}protected JButton createIncreaseButton(int orientation){return createDecreaseButton(orientation);}protected void paintTrack(Graphics g,JComponent c,Rectangle bounds){g.setColor(BG);g.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);}protected void paintThumb(Graphics g,JComponent c,Rectangle bounds){g.setColor(new Color(0x245268));g.fillRoundRect(bounds.x,bounds.y,bounds.width,bounds.height,5,5);}});add(scroll,BorderLayout.CENTER);
        nav.setBackground(new Color(0x061A2C));nav.setBorder(BorderFactory.createMatteBorder(1,0,0,0,new Color(0x18354A)));nav.setPreferredSize(new Dimension(520,72));add(nav,BorderLayout.SOUTH);buildNav();
        timer=new javax.swing.Timer(1000,e->refresh());refresh();
        org.uzel.desktop.messenger.DesktopMessenger.setCallHandler(this::routeCall);
    }
    /** Signaling reaches the chat panel even before the user opens the Chats tab. */
    private void routeCall(org.uzel.messenger.chat.ChatService service,String contactId,String name,int kind,String callId,String sdp){
        SwingUtilities.invokeLater(()->{
            if(kind==org.uzel.messenger.chat.ChatWire.CALL_OFFER&&!previewRendering)showTab(4);
            if(messengerPanel!=null)messengerPanel.deliverCall(service,contactId,name,kind,callId,sdp);
        });
    }
    void open(){frame=new JFrame("Uzelio");frame.setContentPane(this);Rectangle usable=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();frame.setMinimumSize(new Dimension(Math.min(450,usable.width),Math.min(700,usable.height)));frame.setSize(Math.min(520,usable.width),Math.min(900,usable.height));frame.setLocation(usable.x+(usable.width-frame.getWidth())/2,usable.y+(usable.height-frame.getHeight())/2);frame.setIconImage(DesktopMain.icon());frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);frame.addWindowListener(new WindowAdapter(){public void windowClosing(WindowEvent e){if(tray==null)exit();else frame.setVisible(false);}});installTray();timer.start();frame.addNotify();String captionStatus=WindowsCaption.apply(frame);frame.setVisible(true);frame.toFront();if((Boolean.getBoolean("uzelio.previewMetrics")||Boolean.getBoolean("peernet.previewMetrics")))SwingUtilities.invokeLater(()->{try{Files.writeString(root.resolve("window-metrics.txt"),"frame="+frame.getBounds()+"\npage="+page.getSize()+"\nusable="+usable+"\ntransform="+frame.getGraphicsConfiguration().getDefaultTransform()+"\nvisible="+frame.isVisible()+",showing="+frame.isShowing()+",active="+frame.isActive()+",state="+frame.getExtendedState()+"\ncaption="+captionStatus);}catch(Exception ignored){}});}
    private void buildNav(){nav.removeAll();String[] names={"Главная","Чаты","Профиль"},icons={"home","chat","profile"};int[] targets={0,4,3};for(int i=0;i<3;i++){final int target=targets[i],index=i;JButton item=new JButton(){protected void paintComponent(Graphics raw){Graphics2D g=quality(raw);Color c=target==tab||(target==0&&(tab==1||tab==2))?CYAN:MUTED;if(index==1){g.setColor(c);g.setStroke(new BasicStroke(2));g.drawRoundRect(getWidth()/2-12,11,24,18,7,7);g.drawLine(getWidth()/2-7,29,getWidth()/2-11,34);}else DesktopArtwork.paint(g,icons[index],getWidth()/2.0-14,9,28,c);g.setFont(new Font("Segoe UI",target==tab?Font.BOLD:Font.PLAIN,12));g.setColor(c);g.drawString(names[index],(getWidth()-g.getFontMetrics().stringWidth(names[index]))/2,56);if(hasFocus()){g.setColor(CYAN);g.drawRoundRect(5,4,getWidth()-11,getHeight()-9,12,12);}g.dispose();}};item.setContentAreaFilled(false);item.setBorder(null);item.setToolTipText(names[i]);item.getAccessibleContext().setAccessibleName(names[i]);item.addActionListener(e->showTab(target));nav.add(item);}nav.revalidate();nav.repaint();}
    void showTab(int next){tab=next;if(next==4){if(messengerPanel==null)messengerPanel=new org.uzel.desktop.messenger.MessengerPanel(root);scroll.setViewportView(messengerPanel);if(!previewRendering)messengerPanel.refresh();}else scroll.setViewportView(page);page.revalidate();page.repaint();header.revalidate();header.repaint();nav.repaint();scroll.getVerticalScrollBar().setValue(0);if(next==1&&!previewRendering)search();}
    private void refresh(){if(tab==4&&messengerPanel!=null)messengerPanel.refresh();if(refreshPending||observer.isShutdown())return;refreshPending=true;long revision=connectionRevision;observer.submit(()->{try{var next=backend.snapshot();var observed=backend.devices();var transfer=backend.traffic();int apps=backend.selectedApplications().size();var mode=backend.routingMode();var availability=backend.routingAvailability();var routing=backend.routingState();var discovery=backend.discoveryStatus();Set<String> ready=new HashSet<>(),routes=new HashSet<>();for(var d:observed){if(backend.canConnect(d.fingerprint()))ready.add(d.fingerprint());if(backend.hasTrafficRoute(d.fingerprint()))routes.add(d.fingerprint());}SwingUtilities.invokeLater(()->{if(revision!=connectionRevision){refreshPending=false;refresh();return;}if(!transfer.connected()&&!"CONNECTING".equals(transfer.status())&&!"AWAITING_UAC".equals(routing.status()))connectionCancelled=false;state=next;devices=observed;traffic=transfer;selectedCount=apps;routingMode=mode==null?DesktopRoutingMode.SELECTED_APPLICATIONS:mode;routingAvailability=availability;routingState=routing;routingObserved=true;discoveryStatus=discovery==null?new DesktopBackend.DiscoveryStatus("NOT_STARTED","",0):discovery;routedDevices.clear();routedDevices.addAll(routes);connectable.clear();connectable.addAll(ready);refreshPending=false;page.revalidate();page.repaint();});}catch(Exception e){SwingUtilities.invokeLater(()->refreshPending=false);}});}
    private void run(Work action){if(busy)return;busy=true;page.repaint();worker.submit(()->{String failure="";try{action.run();}catch(Exception e){failure=e.getMessage()==null?"Не удалось выполнить действие":e.getMessage();}String result=failure;SwingUtilities.invokeLater(()->{error=result;busy=false;refresh();});});}
    private void toggle(){run(()->{if(backend.snapshot().running())backend.stop();else backend.start(DesktopConfig.load(root));});}
    private void toggleSharing(){if(traffic.sharing()||traffic.measuring()){Thread cancel=new Thread(()->{backend.stopSharing();SwingUtilities.invokeLater(this::refresh);},"uzelio-stop-sharing");cancel.setDaemon(true);cancel.start();}else run(()->{if(!backend.snapshot().running())backend.start(DesktopConfig.load(root));backend.startSharing();});}
    private void cancelConnection(){++connectionRequest;++connectionRevision;connectionPending=false;connectionCancelled=true;connectionFailure="";error="";page.repaint();connectionControl.submit(()->{if(connectionTask!=null)connectionTask.cancel(true);backend.disconnect();SwingUtilities.invokeLater(this::refresh);});}
    private boolean connecting(){return !connectionCancelled&&(connectionPending||connectionFailure.isEmpty()&&("CONNECTING".equals(traffic.status())||"AWAITING_UAC".equals(routingState.status())));}
    private boolean connectionEstablished(){return !connectionCancelled&&traffic.connected()&&routingState.active();}
    private String connectionTitle(){return connecting()?"Подключаемся…":connectionEstablished()?"Соединение установлено":!connectionFailure.isEmpty()?"Не удалось подключиться":traffic.measuring()?"Измеряем скорость":"Не подключено";}
    private String connectionActionTitle(){return connecting()?"Отменить":!connectionCancelled&&traffic.connected()?"Отключиться":!connectionFailure.isEmpty()?"Повторить":"Выбрать узел";}
    private static String bytes(long value){return value>=1_000_000_000?String.format(Locale.forLanguageTag("ru"),"%.1f ГБ",value/1e9):value>=1_000_000?String.format(Locale.forLanguageTag("ru"),"%.1f МБ",value/1e6):String.format(Locale.forLanguageTag("ru"),"%.1f КБ",value/1e3);}
    private static String speed(long value){return String.format(Locale.forLanguageTag("ru"),"%.1f Мбит/с",value/125000.0);}
    private void search(){
        if(searching||discoveryWorker.isShutdown())return;
        searching=true;discoveryError="";page.repaint();
        discoveryWorker.submit(()->{DesktopBackend.SearchResult result=null;String failure="";
            try{if(!backend.snapshot().running())backend.start(DesktopConfig.load(root));result=backend.refreshDevices();}
            catch(Exception e){failure=e.getMessage()==null?"Поиск не завершён":e.getMessage();}
            DesktopBackend.SearchResult answer=result;String problem=failure;
            SwingUtilities.invokeLater(()->{searching=false;discoveryError=problem;if(answer!=null){visited=answer.visitedServers();searchPartial=answer.partial();searched=true;}refresh();page.repaint();});
        });
    }
    private int[] counts(){int[] n=new int[7];for(var d:devices){n[0]++;n[DesktopRegions.group(d.country())]++;}return n;}
    private java.util.List<DeviceDirectoryView.Device> filtered(){return devices.stream().filter(d->region==0||DesktopRegions.group(d.country())==region).toList();}
    private interface Work{void run()throws Exception;}
    private record Hit(String title,Rectangle bounds,Runnable action,boolean enabled){}
    private final class Page extends JPanel implements Scrollable {
        private final JProgressBar connectionProgress=new JProgressBar();
        private final java.util.List<Hit> hits=new ArrayList<>();
        private final java.util.List<JButton> controls=new ArrayList<>();
        Page(){setLayout(null);setBackground(BG);setFocusable(true);connectionProgress.setForeground(CYAN);connectionProgress.setBackground(new Color(0x18354A));connectionProgress.setBorderPainted(false);connectionProgress.setVisible(false);connectionProgress.getAccessibleContext().setAccessibleName("Подключаемся…");add(connectionProgress);addMouseWheelListener(e->{if(tab==1&&e.getY()>=282&&e.getY()<325){chipOffset=Math.max(0,Math.min(7*114-(getWidth()-40),chipOffset+e.getWheelRotation()*65));repaint();e.consume();}});}
        public Dimension getPreferredSize(){int width=Math.max(400,scroll==null?480:scroll.getViewport().getWidth());int height=switch(tab){case 0->error.isEmpty()?790:860;case 1->Math.max(600,430+filtered().size()*113+(searched?45:0));case 2->error.isEmpty()?550:610;default->error.isEmpty()?790:860;};return new Dimension(width,height);}
        public Dimension getPreferredScrollableViewportSize(){return getPreferredSize();}public int getScrollableUnitIncrement(Rectangle r,int o,int d){return 22;}public int getScrollableBlockIncrement(Rectangle r,int o,int d){return 150;}public boolean getScrollableTracksViewportWidth(){return true;}public boolean getScrollableTracksViewportHeight(){return false;}
        protected void paintComponent(Graphics raw){super.paintComponent(raw);hits.clear();boolean pending=tab==0&&connecting();connectionProgress.setIndeterminate(pending);connectionProgress.setVisible(pending);connectionProgress.setBounds(135,108,Math.max(80,getWidth()-170),4);getAccessibleContext().setAccessibleDescription(connectionTitle());Graphics2D g=quality(raw);int width=getWidth(),x=20,w=width-40;switch(tab){case 0->home(g,x,w);case 1->map(g,x,w);case 2->traffic(g,x,w);default->profile(g,x,w);}g.dispose();java.util.List<Hit> current=java.util.List.copyOf(hits);SwingUtilities.invokeLater(()->syncControls(current));}
        private void syncControls(java.util.List<Hit> actions){while(controls.size()<actions.size()){JButton b=transparentButton();b.addActionListener(e->{Hit hit=(Hit)b.getClientProperty("action");if(hit!=null&&hit.enabled)hit.action.run();});controls.add(b);add(b);}for(int i=0;i<controls.size();i++){JButton b=controls.get(i);b.setVisible(i<actions.size());if(i<actions.size()){Hit hit=actions.get(i);b.setBounds(hit.bounds);b.setEnabled(hit.enabled);b.putClientProperty("action",hit);b.getAccessibleContext().setAccessibleName(hit.title);b.setToolTipText(hit.title);}}}
        private void action(Graphics2D g,String title,int x,int y,int w,int h,boolean primary,boolean enabled,Runnable callback){button(g,title,x,y,w,h,primary,enabled);hits.add(new Hit(title,new Rectangle(x,y,w,h),callback,enabled&&!busy));}
        private void home(Graphics2D g,int x,int w){
            card(g,x,8,w,174);DesktopArtwork.paint(g,"network",x+9,17,99,CYAN);
            int titleSize=w<410?18:21;
            text(g,connectionTitle(),x+115,55,titleSize,TEXT,true);
            text(g,connecting()?"к "+(connectionPeer.isEmpty()?"узлу сети":connectionPeer.substring(0,Math.min(16,connectionPeer.length()))):connectionEstablished()?(routingMode==DesktopRoutingMode.ALL_TRAFFIC?"TCP/UDP всей системы":"TCP/UDP выбранных приложений"):traffic.sharing()?"Раздача включена":"Раздача выключена",x+115,80,w<410?11:12,MUTED,false);
            text(g,traffic.measuring()?"Можно остановить замер":traffic.sharing()?(traffic.shareRateLimit()==0?"Без ограничения":speed(traffic.shareRateLimit())):"Ваш трафик — под вашим контролем",x+115,102,w<410?11:12,MUTED,false);
            boolean connecting=connecting();
            int bw=(w-36)/2;action(g,connectionActionTitle(),x+12,122,bw,46,false,true,()->{if(traffic.connected()&&!connectionCancelled||connecting)cancelConnection();else if(!connectionFailure.isEmpty())connectPeer(connectionPeer);else showTab(1);});
            if(traffic.connected()||connecting){int last=hits.size()-1;Hit h=hits.get(last);hits.set(last,new Hit(h.title,h.bounds,h.action,true));}
            String shareTitle=traffic.sharing()||traffic.measuring()?"Остановить раздачу":"Включить раздачу";action(g,shareTitle,x+24+bw,122,bw,46,true,true,DesktopWindow.this::toggleSharing);
            if(traffic.sharing()||traffic.measuring()){int last=hits.size()-1;Hit h=hits.get(last);hits.set(last,new Hit(h.title,h.bounds,h.action,true));}
            card(g,x,195,w,74);int cw=w/3;String[] labels={"Получено","Отдано","Подключено"},values={bytes(traffic.receivedBytes()),bytes(traffic.sharedBytes()),traffic.clients()+" из 3"};for(int i=0;i<3;i++){if(i>0){g.setColor(new Color(0x28435A));g.drawLine(x+i*cw,212,x+i*cw,250);}text(g,labels[i],x+15+i*cw,220,12,MUTED,false);text(g,values[i],x+15+i*cw,249,18,TEXT,true);}
            card(g,x,282,w,272);text(g,"Узлы по всему миру",x+16,311,17,TEXT,true);DesktopArtwork.paint(g,"chevron",x+w-35,291,20,MUTED);
            DesktopWorldMap.paint(g,x+8,322,w-16,167,counts(),region);action(g,"Открыть карту",x+13,497,w-26,44,false,true,()->showTab(1));
            card(g,x,567,w,98);text(g,"Режим подключения",x+16,596,17,TEXT,true);DesktopArtwork.paint(g,"chevron",x+w-35,577,20,MUTED);DesktopArtwork.paint(g,"apps",x+16,609,34,CYAN);text(g,routingMode==DesktopRoutingMode.ALL_TRAFFIC?"Вся система":needsApplications()?"Выберите приложения":"Выбрано: "+selectedCount,x+60,635,14,MUTED,false);hits.add(new Hit("Режим подключения",new Rectangle(x,567,w,98),DesktopWindow.this::routingModeDialog,!busy));
            action(g,"Трафик и скорость",x,680,w,48,false,true,()->showTab(2));wrapped(g,routingHint(),x+5,755,w-10,13,MUTED);if(!error.isEmpty())wrapped(g,error,x+5,805,w-10,13,new Color(0xFF8E9A));
        }
        private void map(Graphics2D g,int x,int w){
            text(g,"Найдено: "+devices.size()+" · доступная часть сети",x,26,14,MUTED,false);
            boolean prerequisite=traffic.measuring()||!traffic.sharing()||needsApplications()||!routingAvailability.installed();
            if(prerequisite){String title=traffic.measuring()?"Дождитесь замера":!traffic.sharing()?"Включить раздачу":needsApplications()?"Выбрать приложения":!routingAvailability.supported()?"Нужна Windows x64":"Компонент Windows";action(g,title,x,40,w,40,false,!traffic.measuring()&&(routingAvailability.supported()||!traffic.sharing()||needsApplications()),DesktopWindow.this::resolvePrerequisite);}
            DesktopWorldMap.paint(g,0,prerequisite?88:40,getWidth(),prerequisite?184:232,counts(),region);
            if(!devices.isEmpty()) {
            String[] names={"Все","Европа","Азия","Америка","Африка","Океания","Не указан"};int[] order={0,4,3,2,1,5,6};
            Shape clip=g.getClip();g.clipRect(x,279,w,47);for(int i=0;i<names.length;i++){int r=order[i],cx=x+i*114-chipOffset;button(g,names[i],cx,282,106,37,region==r,true);Rectangle visible=new Rectangle(cx,282,106,37).intersection(new Rectangle(x,279,w,47));if(!visible.isEmpty())hits.add(new Hit(names[i],visible,()->{region=r;revalidate();repaint();},!busy));}g.setClip(clip);
            // Arrow controls keep all filter regions available with mouse and keyboard.
            if(chipOffset>0)action(g,"‹",x,329,40,30,false,true,()->{chipOffset=Math.max(0,chipOffset-228);repaint();});if(chipOffset<Math.max(0,7*114-w))action(g,"›",x+w-40,329,40,30,false,true,()->{chipOffset=Math.min(7*114-w,chipOffset+228);repaint();});
            }
            int y=devices.isEmpty()?295:365;var list=filtered();
            if(list.isEmpty()){
                var empty=DesktopDiscoveryPresentation.empty(discoveryStatus.state(),searching,!discoveryError.isEmpty(),region!=0&&!devices.isEmpty());
                Color tint="offline".equals(empty.artwork())?new Color(0xFFC978):CYAN;DesktopArtwork.paint(g,empty.artwork(),getWidth()/2.0-55,y,110,tint);
                centered(g,empty.title(),y+139,24,TEXT,true);centered(g,empty.hint(),y+166,14,MUTED,false);
                boolean pending=searching||"SEARCHING".equals(discoveryStatus.state());
                action(g,empty.action(),x+32,y+185,w-64,48,true,!pending,()->{if("NOT_CONFIGURED".equals(discoveryStatus.state()))showTab(3);else if(region!=0&&!devices.isEmpty()){region=0;page.revalidate();page.repaint();}else search();});
                int last=hits.size()-1;Hit query=hits.get(last);hits.set(last,new Hit(query.title,query.bounds,query.action,!pending));
            }else{action(g,searching?"Поиск…":"Обновить поиск",x,y,w,44,true,!searching,DesktopWindow.this::search);y+=59;for(var d:list){card(g,x,y,w,100);String country=DeviceDirectoryView.regionName(d.country());text(g,country,x+17,y+29,18,TEXT,true);text(g,d.advertisedUpload()>0?"Свободно "+Math.max(0,3-d.clients())+" из 3":"Нагрузка не проверена",x+17,y+54,13,MUTED,false);boolean ready=connectable.contains(d.fingerprint());text(g,connectionReason(d.fingerprint()),x+17,y+78,12,MUTED,false);
                String prerequisiteTitle=prerequisiteActionTitle();int buttonWidth=prerequisiteTitle==null?120:190;
                boolean canResolve=routingObserved&&!traffic.measuring()&&(!traffic.sharing()||needsApplications()||routingAvailability.supported());
                action(g,prerequisiteTitle==null?"Подключить":prerequisiteTitle,x+w-buttonWidth-15,y+43,buttonWidth,48,true,prerequisiteTitle==null?ready:canResolve,prerequisiteTitle==null?()->connectPeer(d.fingerprint()):DesktopWindow.this::resolvePrerequisite);y+=113;}}
            if(searched)text(g,"Проверено каталогов: "+visited+(searchPartial?" · показана часть сети":" · запрос завершён"),x,list.isEmpty()?y+253:y+20,12,MUTED,false);
        }
        private void traffic(Graphics2D g,int x,int w){String[] titles={"Получено","Отдано","Измеренная отдача"},values={bytes(traffic.receivedBytes()),bytes(traffic.sharedBytes()),traffic.measuring()?"Измеряем…":traffic.measuredUpload()>0?speed(traffic.measuredUpload()):"Нет замера"};for(int i=0;i<3;i++){card(g,x,8+i*118,w,104);text(g,titles[i],x+18,40+i*118,14,MUTED,false);text(g,values[i],x+18,86+i*118,28,TEXT,true);}action(g,"Измерить скорость",x,375,w,48,true,traffic.sharing()&&!traffic.measuring(),()->run(backend::measureUpload));action(g,"Скорость раздачи",x,437,w,48,false,true,DesktopWindow.this::rateDialog);if(!error.isEmpty())wrapped(g,error,x,510,w,13,new Color(0xFF8E9A));}
        private void profile(Graphics2D g,int x,int w){
            card(g,x,8,w,116);DesktopArtwork.paint(g,"network",x+9,19,88,CYAN);text(g,"Участник Uzelio",x+111,48,21,TEXT,true);text(g,state.running()?"Каталог работает":"Каталог остановлен",x+111,76,14,MUTED,false);text(g,traffic.sharing()?"Раздача включена":"Раздача выключена",x+111,98,13,MUTED,false);
            action(g,state.running()?"Остановить участие":"Запустить участие",x,138,w,50,true,true,DesktopWindow.this::toggle);
            int half=(w-12)/2;text(g,"Поиск устройств",x+5,218,14,MUTED,false);
            action(g,"Мой код каталога",x,229,half,48,false,state.running(),DesktopWindow.this::copyCode);
            action(g,"Добавить в каталог",x+half+12,229,half,48,false,true,DesktopWindow.this::joinCode);
            text(g,"Прямое подключение к раздаче",x+5,312,14,MUTED,false);
            action(g,"Код раздачи",x,323,half,48,false,traffic.sharing()&&!traffic.measuring(),()->exportCode(true));
            action(g,"Добавить код узла",x+half+12,323,half,48,false,true,()->importCode(true));
            action(g,"Режим подключения",x,385,w,48,false,true,DesktopWindow.this::routingModeDialog);
            action(g,"Скорость раздачи",x,447,w,48,false,true,DesktopWindow.this::rateDialog);
            action(g,"Компонент Windows",x,509,half,48,false,true,DesktopWindow.this::routingDialog);
            action(g,"Дополнительно",x+half+12,509,half,48,false,!state.running(),DesktopWindow.this::settingsDialog);
            wrapped(g,"Входящая доступность не проверена. Для внешних подключений нужен доступный адрес.",x+6,592,w-12,14,MUTED);
            action(g,"Выйти из Uzelio",x,662,w,50,false,true,DesktopWindow.this::exit);int last=hits.size()-1;Hit exit=hits.get(last);hits.set(last,new Hit(exit.title,exit.bounds,exit.action,true));
            if(!error.isEmpty())wrapped(g,error,x+5,752,w-10,13,new Color(0xFF8E9A));
        }
        private void centered(Graphics2D g,String value,int y,int size,Color c,boolean bold){g.setFont(font(size,bold));text(g,value,(getWidth()-g.getFontMetrics().stringWidth(value))/2,y,size,c,bold);}
    }
    private static Font font(int size,boolean bold){return new Font("Segoe UI",bold?Font.BOLD:Font.PLAIN,size);}
    private static JButton transparentButton(){JButton b=new JButton(){protected void paintComponent(Graphics raw){if(hasFocus()){Graphics2D g=quality(raw);g.setColor(CYAN);g.setStroke(new BasicStroke(2));g.drawRoundRect(2,2,getWidth()-5,getHeight()-5,14,14);g.dispose();}}};b.setContentAreaFilled(false);b.setBorder(null);b.setOpaque(false);b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));return b;}
    private static void text(Graphics2D g,String value,int x,int y,int size,Color c,boolean bold){g.setFont(font(size,bold));g.setColor(c);g.drawString(value,x,y);}
    private static void wrapped(Graphics2D g,String value,int x,int y,int width,int size,Color color){g.setFont(font(size,false));g.setColor(color);String line="";for(String word:value.split(" ")){String next=line.isEmpty()?word:line+" "+word;if(g.getFontMetrics().stringWidth(next)>width&&!line.isEmpty()){g.drawString(line,x,y);y+=size+6;line=word;}else line=next;}g.drawString(line,x,y);}
    private static Graphics2D quality(Graphics raw){Graphics2D g=(Graphics2D)raw.create();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);return g;}
    private static void card(Graphics2D g,int x,int y,int w,int h){g.setColor(new Color(0,5,12,120));g.fillRoundRect(x,y+5,w,h,44,44);g.setPaint(new GradientPaint(x,y,new Color(0x102D43),x+w,y+h,new Color(0x092135)));g.fillRoundRect(x,y,w,h,44,44);g.setColor(new Color(0x32536A));g.drawRoundRect(x,y,w-1,h-1,44,44);g.setColor(new Color(84,140,163,45));g.drawRoundRect(x+1,y+1,w-3,h-3,42,42);}
    private static void button(Graphics2D g,String title,int x,int y,int w,int h,boolean primary,boolean enabled){g.setColor(new Color(0,4,10,160));g.fillRoundRect(x,y+4,w,h,32,32);Color top=primary?new Color(0x55FFFF):new Color(0x234B63),bottom=primary?new Color(0x04B8CD):new Color(0x102C42);if(!enabled){top=new Color(0x153449);bottom=new Color(0x102839);}g.setPaint(new GradientPaint(x,y,top,x,y+h,bottom));g.fillRoundRect(x,y,w,h-2,32,32);g.setColor(primary&&enabled?new Color(0x83FFFF):new Color(0x3B6076));g.drawRoundRect(x+1,y+1,w-3,h-4,30,30);g.setFont(font(14,true));g.setColor(!enabled?new Color(0x8296A9):primary?BG:TEXT);FontMetrics fm=g.getFontMetrics();g.drawString(title,x+(w-fm.stringWidth(title))/2,y+(h+fm.getAscent()-fm.getDescent())/2-1);}
    private JPanel form(){JPanel p=new JPanel();p.setBackground(new Color(0x092135));p.setLayout(new BoxLayout(p,BoxLayout.Y_AXIS));p.setBorder(BorderFactory.createEmptyBorder(20,20,20,20));return p;}
    private void connectPeer(String fingerprint){
        if(needsApplications()){applicationsDialog();return;}
        if(busy)return;
        long request=++connectionRequest;++connectionRevision;
        connectionPending=true;connectionCancelled=false;connectionFailure="";connectionPeer=fingerprint;error="";showTab(0);
        connectionControl.submit(()->{
            if(connectionTask!=null)connectionTask.cancel(true);backend.disconnect();
            connectionTask=connectionWorker.submit(()->{
                String failure="";try{backend.connect(fingerprint);}catch(Exception e){failure=e.getMessage()==null?"Не удалось подключиться":e.getMessage();}
                String result=failure;var completedTraffic=backend.traffic();var completedRouting=backend.routingState();SwingUtilities.invokeLater(()->{if(connectionRequest==request){++connectionRevision;traffic=completedTraffic;routingState=completedRouting;routingObserved=true;connectionPending=false;connectionFailure=result;error=result;refresh();page.repaint();}});
            });
        });
    }
    private boolean needsApplications(){return routingMode==DesktopRoutingMode.SELECTED_APPLICATIONS && selectedCount==0;}
    private void routingModeDialog(){
        JPanel panel=form();panel.add(label("Режим подключения"));
        JRadioButton selected=radio("Выбранные приложения",routingMode==DesktopRoutingMode.SELECTED_APPLICATIONS),whole=radio("Вся система",routingMode==DesktopRoutingMode.ALL_TRAFFIC);
        ButtonGroup group=new ButtonGroup();group.add(selected);group.add(whole);panel.add(selected);panel.add(whole);
        panel.add(note("TCP/UDP выбранных приложений или всей системы, для всех пользователей Windows. Список приложений сохраняется при смене режима.",3));
        panel.add(note("Uzelio, сетевой компонент, VPN и службы Windows идут напрямую. DNS, неопознанные процессы, фрагменты IPv6 и сбой компонента также могут оставить прямой выход.",4));
        JDialog dialog=dialog("Режим подключения",panel);
        panel.add(formButton("Выбрать приложения",()->{dialog.dispose();applicationsDialog();}));
        panel.add(formButton("Сохранить",()->run(()->{backend.setRoutingMode(whole.isSelected()?DesktopRoutingMode.ALL_TRAFFIC:DesktopRoutingMode.SELECTED_APPLICATIONS);SwingUtilities.invokeLater(dialog::dispose);})));
        dialog.setSize(460,530);dialog.setLocationRelativeTo(frame);dialog.setVisible(true);
    }
    private String prerequisiteActionTitle(){
        if(!routingObserved)return "Проверяем настройки";
        if(traffic.measuring())return "Дождитесь замера";
        if(!traffic.sharing())return "Включить раздачу";
        if(needsApplications())return "Выбрать приложения";
        if(!routingAvailability.supported())return "Нужна Windows x64";
        return routingAvailability.installed()?null:"Компонент Windows";
    }
    private String connectionReason(String fingerprint){if(!routedDevices.contains(fingerprint)&&connectable.contains(fingerprint))return "Автоматическое сопряжение";return routingObserved?DesktopConnectionPresentation.reason(traffic.sharing(),traffic.measuring(),routingMode==DesktopRoutingMode.ALL_TRAFFIC?1:selectedCount,routingAvailability.supported(),routingAvailability.installed(),connectable.contains(fingerprint)):"Проверяем настройки";}
    private String routingHint(){
        if(!routingObserved)return "Проверяем сетевой компонент…";
        if(!routingAvailability.supported())return "Маршрутизация приложений требует Windows x64";
        if(!routingAvailability.installed())return "Нужен сетевой компонент Windows · подробнее в профиле";
        if("AWAITING_UAC".equals(routingState.status()))return "Ожидаем подтверждение Windows";
        if("FAILED".equals(routingState.status()))return "Компонент остановлен · приложения могут идти напрямую";
        return connectionEstablished()?"Компонент запущен · проверьте связь приложений":needsApplications()?"Выберите приложения для подключения":"Компонент установлен · выберите узел";
    }
    private void resolvePrerequisite(){if(traffic.measuring())return;if(!traffic.sharing())toggleSharing();else if(needsApplications())applicationsDialog();else routingDialog();}
    private void routingDialog(){JPanel panel=form();panel.add(label("Сетевой компонент Windows"));panel.add(note(routingAvailability.installed()?"Компонент установлен. При подключении к узлу Windows может запросить подтверждение.":"UPDATE_WINDOWS_ROUTING_SETUP".equals(routingAvailability.detail())?"Нужно обновить сетевой компонент для Uzelio. Потребуется разрешение Windows.":routingAvailability.supported()?"Для выбранных приложений нужна однократная установка. Потребуется разрешение Windows.":"Маршрутизация приложений поддерживается в Windows x64.",3));panel.add(note("Выбор применяется к именам процессов для всех пользователей. DNS использует обычную сеть. Фрагменты IPv6 и сбой компонента также могут оставить прямой выход.",3));JDialog d=dialog("Компонент Windows",panel);JButton setup=routingSetupAction(routingAvailability,this::routingSetupDialog);if(setup!=null)panel.add(setup);panel.add(formButton("Понятно",d::dispose));d.setSize(440,410);d.setVisible(true);}
    private void routingSetupDialog(){routingSetupDialog(()->{});}
    private void routingSetupDialog(Runnable onInstalled){JPanel panel=form();panel.add(label("Установка сетевого компонента"));panel.add(note("Потребуется подтверждение Windows. Устанавливаются ProxiFyre и драйвер Windows Packet Filter.",2));panel.add(note("ProxiFyre — AGPL-3.0. Драйвер бесплатен для личного использования; для коммерческого нужна отдельная лицензия.",3));panel.add(link("Лицензия ProxiFyre", "https://github.com/wiresock/proxifyre/blob/5e3bc845d73a47a0931b1233c745580cb916f4ba/LICENSE"));panel.add(link("Условия Windows Packet Filter", "https://www.ntkernel.com/windows-packet-filter/"));JLabel status=label(" ");panel.add(status);JDialog d=dialog("Условия установки",panel);JButton install=formButton("Принять условия и установить",()->{status.setText("Ожидаем подтверждение Windows…");run(()->{try{WindowsRoutingSetup.install();SwingUtilities.invokeLater(()->{status.setText("Компонент установлен");onInstalled.run();refresh();});}catch(Exception error){SwingUtilities.invokeLater(()->status.setText(error.getMessage()));}});});panel.add(install);panel.add(formButton("Закрыть",d::dispose));d.setSize(450,460);d.setLocationRelativeTo(frame);d.setVisible(true);}
    private JLabel link(String title,String url){JLabel l=label(title);l.setForeground(CYAN);l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));l.setToolTipText(url);l.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent event){try{if(Desktop.isDesktopSupported())Desktop.getDesktop().browse(java.net.URI.create(url));}catch(Exception ignored){}}});return l;}
    private void applicationsDialog(){run(()->{
        var installed=DesktopApplications.discover();var saved=backend.selectedApplications();var availability=backend.routingAvailability();
        SwingUtilities.invokeLater(()->{
            JPanel panel=form();panel.add(label("Через Uzelio — выбранные приложения"));
            panel.add(note("Остальные используют обычную сеть. Выбор относится ко всем копиям программы с таким именем.",2));
            JLabel status=label(availability.installed()?"Компонент Windows установлен":availability.supported()?"Нужен сетевой компонент Windows":"Нужна Windows x64");panel.add(status);
            JTextField filter=field("");filter.setToolTipText("Поиск приложения");filter.getAccessibleContext().setAccessibleName("Поиск приложения");panel.add(filter);
            Map<String,DesktopApplications.App> catalog=new LinkedHashMap<>();Set<Path> chosen=new HashSet<>(saved);
            for(Path path:saved){var app=new DesktopApplications.App(path.getFileName().toString(),path);catalog.put(app.processName(),app);}
            for(var app:installed)catalog.putIfAbsent(app.processName(),app);
            DefaultListModel<DesktopApplications.App> model=new DefaultListModel<>();JList<DesktopApplications.App> list=new JList<>(model);
            list.setBackground(BG);list.setForeground(TEXT);list.setFixedCellHeight(50);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.getAccessibleContext().setAccessibleName("Приложения. Пробел переключает выбор");
            list.setCellRenderer((view,app,index,focused,cellFocus)->{
                JPanel row=new JPanel(new BorderLayout(12,0));row.setBackground(focused?new Color(0x12354B):BG);row.setBorder(BorderFactory.createEmptyBorder(7,10,7,12));
                Icon icon=applicationIcons.get(app.executable());JLabel image=new JLabel(icon);image.setPreferredSize(new Dimension(28,28));row.add(image,BorderLayout.WEST);
                JLabel name=label(app.name());name.setForeground(chosen.contains(app.executable())?CYAN:TEXT);row.add(name);
                JLabel check=new JLabel(chosen.contains(app.executable())?"✓":"○");check.setForeground(chosen.contains(app.executable())?CYAN:MUTED);check.setFont(font(23,true));row.add(check,BorderLayout.EAST);
                row.getAccessibleContext().setAccessibleName(app.name()+(chosen.contains(app.executable())?", выбрано":", не выбрано"));return row;
            });
            Runnable toggle=()->{var app=list.getSelectedValue();if(app!=null){if(!chosen.add(app.executable()))chosen.remove(app.executable());list.repaint();}};
            list.addMouseListener(new MouseAdapter(){public void mouseClicked(MouseEvent e){int index=list.locationToIndex(e.getPoint());if(index>=0&&list.getCellBounds(index,index).contains(e.getPoint())){list.setSelectedIndex(index);toggle.run();}}});
            list.getInputMap().put(KeyStroke.getKeyStroke("SPACE"),"toggle-choice");list.getActionMap().put("toggle-choice",new AbstractAction(){public void actionPerformed(ActionEvent e){toggle.run();}});
            JScrollPane holder=new JScrollPane(list);holder.setBorder(BorderFactory.createLineBorder(new Color(0x28435A)));holder.setAlignmentX(LEFT_ALIGNMENT);holder.setPreferredSize(new Dimension(420,290));holder.getVerticalScrollBar().setUnitIncrement(24);panel.add(holder);
            java.util.concurrent.atomic.AtomicBoolean iconPending=new java.util.concurrent.atomic.AtomicBoolean();
            Runnable loadIcons=()->{
                if(iconWorker.isShutdown()||!list.isDisplayable()||!iconPending.compareAndSet(false,true))return;
                int first=Math.max(0,list.getFirstVisibleIndex()),last=Math.min(model.size()-1,Math.max(first+10,list.getLastVisibleIndex()));java.util.List<DesktopApplications.App> batch=new ArrayList<>();
                for(int index=first;index<=last&&batch.size()<24;index++){var app=model.get(index);if(!applicationIcons.containsKey(app.executable()))batch.add(app);}
                if(batch.isEmpty()){iconPending.set(false);return;}
                iconWorker.submit(()->{for(var app:batch){if(!list.isDisplayable())break;try{Icon icon=javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemIcon(app.executable().toFile(),24,24);if(icon!=null)applicationIcons.put(app.executable(),icon);}catch(RuntimeException ignored){}}SwingUtilities.invokeLater(()->{iconPending.set(false);list.repaint();});});
            };
            Runnable update=()->{String query=filter.getText().toLowerCase(Locale.ROOT).trim();model.clear();for(var app:catalog.values())if((app.name()+" "+app.processName()).toLowerCase(Locale.ROOT).contains(query))model.addElement(app);if(!model.isEmpty())list.setSelectedIndex(0);SwingUtilities.invokeLater(loadIcons);};
            filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener(){public void insertUpdate(javax.swing.event.DocumentEvent e){update.run();}public void removeUpdate(javax.swing.event.DocumentEvent e){update.run();}public void changedUpdate(javax.swing.event.DocumentEvent e){update.run();}});
            holder.getViewport().addChangeListener(e->loadIcons.run());update.run();
            panel.add(note("DNS использует обычную сеть. Фрагменты IPv6 и сбой компонента также могут оставить прямой выход.",2));
            JDialog d=dialog("Приложения",panel);
            if(availability.supported()&&!availability.installed())panel.add(formButton("Установить сетевой компонент",()->routingSetupDialog(()->status.setText("Компонент Windows установлен"))));
            panel.add(formButton("Добавить .exe",()->{JFileChooser picker=new JFileChooser();picker.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Приложение Windows (*.exe)","exe"));if(picker.showOpenDialog(d)==JFileChooser.APPROVE_OPTION){Path selected=picker.getSelectedFile().toPath();run(()->{try{Path canonical=DesktopApplications.canonicalSelection(java.util.List.of(selected)).get(0);var app=new DesktopApplications.App(canonical.getFileName().toString(),canonical);SwingUtilities.invokeLater(()->{catalog.putIfAbsent(app.processName(),app);chosen.add(catalog.get(app.processName()).executable());update.run();});}catch(Exception error){SwingUtilities.invokeLater(()->status.setText("Не удалось добавить этот .exe"));}});}}));
            panel.add(formButton("Сохранить",()->{java.util.List<Path> selected=java.util.List.copyOf(chosen);run(()->{try{backend.selectApplications(selected);SwingUtilities.invokeLater(d::dispose);}catch(Exception error){SwingUtilities.invokeLater(()->status.setText("Не удалось сохранить выбор приложений"));}});}));
            Rectangle usable=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();d.setSize(Math.min(510,usable.width),Math.min(740,usable.height));d.setLocationRelativeTo(frame);SwingUtilities.invokeLater(loadIcons);d.setVisible(true);
        });
    });}
    private JLabel label(String value){JLabel l=new JLabel(value);l.setFont(font(15,false));l.setForeground(TEXT);l.setAlignmentX(LEFT_ALIGNMENT);return l;}
    private JTextArea note(String value,int rows){JTextArea area=new JTextArea(value);area.setEditable(false);area.setFocusable(false);area.setLineWrap(true);area.setWrapStyleWord(true);area.setRows(rows);area.setFont(font(13,false));area.setForeground(MUTED);area.setOpaque(false);area.setAlignmentX(LEFT_ALIGNMENT);area.setMaximumSize(new Dimension(600,rows*21+8));return area;}
    private JTextField field(String value){JTextField f=new JTextField(value);f.setFont(font(16,false));f.setBackground(BG);f.setForeground(TEXT);f.setCaretColor(CYAN);f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0x34566B)),BorderFactory.createEmptyBorder(10,12,10,12)));f.setAlignmentX(LEFT_ALIGNMENT);f.setMaximumSize(new Dimension(600,46));return f;}
    private JRadioButton radio(String title,boolean selected){JRadioButton r=new JRadioButton(title,selected){protected void paintComponent(Graphics raw){Graphics2D g=quality(raw);g.setColor(isSelected()?CYAN:MUTED);g.setStroke(new BasicStroke(1.7f));g.drawOval(5,11,19,19);if(isSelected())g.fillOval(10,16,10,10);text(g,getText(),36,27,16,TEXT,false);if(hasFocus()){g.setColor(CYAN);g.drawRoundRect(1,1,getWidth()-3,getHeight()-3,12,12);}g.dispose();}};r.setOpaque(false);r.setContentAreaFilled(false);r.setBorder(null);r.setPreferredSize(new Dimension(380,42));r.setMaximumSize(new Dimension(600,42));return r;}
    static JButton routingSetupAction(WindowsAppRouter.Availability availability,Runnable callback){return availability.supported()?formButton(availability.installed()?"Обновить компонент":"Установить сетевой компонент",callback):null;}
    private static JButton formButton(String title,Runnable callback){JButton b=new JButton(title){protected void paintComponent(Graphics raw){Graphics2D g=quality(raw);button(g,getText(),0,0,getWidth(),getHeight()-3,true,isEnabled());g.dispose();}};b.setContentAreaFilled(false);b.setBorder(null);b.setAlignmentX(LEFT_ALIGNMENT);b.setPreferredSize(new Dimension(380,50));b.setMaximumSize(new Dimension(600,50));b.addActionListener(e->callback.run());return b;}
    private JDialog dialog(String title,JPanel panel){JDialog d=new JDialog(frame,title,true);d.setContentPane(panel);d.setSize(440,420);d.setLocationRelativeTo(frame);d.addNotify();WindowsCaption.apply(d);return d;}
    private void copyCode(){exportCode(false);}
    private void joinCode(){importCode(false);}
    private void exportCode(boolean direct){run(()->{String code=direct?backend.sharingCode():backend.ownCode();SwingUtilities.invokeLater(()->{JPanel panel=form();panel.add(label(direct?"Код моей раздачи":"Мой код каталога"));panel.add(note(direct?"Для прямого подключения к вашей раздаче. Передавайте код только выбранному участнику.":"Для поиска устройств через этот каталог. Код каталога не разрешает раздачу трафика.",2));JTextField value=field(code);value.setEditable(false);panel.add(value);JDialog d=dialog("Uzelio",panel);panel.add(Box.createVerticalStrut(15));panel.add(formButton("Скопировать",()->{Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code),null);d.dispose();}));d.setSize(440,280);d.setVisible(true);});});}
    private void importCode(boolean direct){JPanel panel=form();panel.add(label(direct?"Код раздачи участника":"Код каталога участника"));panel.add(note(direct?"Добавит прямой маршрут. Подключение включается отдельно после выбора узла.":"Добавит контакт каталога для поиска устройств.",2));JTextField code=field("");panel.add(code);JLabel problem=label(" ");problem.setForeground(new Color(0xFF8E9A));panel.add(problem);JDialog d=dialog(direct?"Добавить узел раздачи":"Добавить каталог",panel);JButton add=formButton("Добавить",()->{String value=code.getText().trim();if(value.isEmpty()){problem.setText("Вставьте полный код Uzelio");return;}run(()->{try{if(direct)backend.addTrafficPeer(value);else{if(!backend.snapshot().running())backend.start(DesktopConfig.load(root));backend.addContact(value);}SwingUtilities.invokeLater(()->{d.dispose();if(direct)showTab(1);});}catch(Exception error){SwingUtilities.invokeLater(()->problem.setText("Не удалось добавить код: "+error.getMessage()));}});});panel.add(add);d.setSize(440,300);d.setVisible(true);}
    private void settingsDialog(){try{DesktopConfig cfg=DesktopConfig.load(root);JPanel panel=form();panel.add(label("Адрес для входящих подключений"));JTextField host=field(cfg.advertisedHost()),port=field(Integer.toString(cfg.port()));panel.add(host);panel.add(label("Порт"));panel.add(port);JCheckBox lan=new JCheckBox("Разрешить частную сеть",cfg.allowPrivate());lan.setOpaque(false);lan.setForeground(TEXT);lan.setFont(font(15,false));panel.add(lan);JLabel problem=label(" ");problem.setForeground(new Color(0xFF8E9A));panel.add(problem);JDialog d=dialog("Дополнительно",panel);panel.add(formButton("Сохранить",()->{try{new DesktopConfig(host.getText(),Integer.parseInt(port.getText().trim()),lan.isSelected()).save(root);d.dispose();}catch(Exception e){problem.setText("Проверьте адрес и порт");}}));d.setVisible(true);}catch(Exception e){error=e.getMessage();}}
    private void rateDialog(){try{
        SharingRatePreference saved=SharingRatePreference.load(root);JPanel panel=form();panel.add(label("Скорость раздачи"));
        panel.add(label(traffic.measuredUpload()>0?"Минимум: "+speed(SharingRatePreference.minimumBytesPerSecond(traffic.measuredUpload())):"Минимум определится при включении раздачи"));
        panel.add(Box.createVerticalStrut(12));JRadioButton unlimited=radio("Без ограничения",saved.bytesPerSecond()==0),limited=radio("Лимит скорости",saved.bytesPerSecond()!=0);
        ButtonGroup group=new ButtonGroup();group.add(unlimited);group.add(limited);panel.add(unlimited);panel.add(limited);
        JTextField value=field(saved.bytesPerSecond()==0?"":saved.mbps());value.setEnabled(limited.isSelected());panel.add(value);panel.add(label("Мбит/с · не ниже трети измеренной отдачи"));
        JLabel problem=label(" ");problem.setForeground(new Color(0xFF8E9A));panel.add(problem);
        limited.addActionListener(e->value.setEnabled(true));unlimited.addActionListener(e->value.setEnabled(false));JDialog d=dialog("Скорость раздачи",panel);
        panel.add(formButton("Сохранить",()->{try{var preference=unlimited.isSelected()?SharingRatePreference.unlimited():SharingRatePreference.manualMbps(value.getText(),traffic.measuredUpload());run(()->{backend.setSharingRate(preference);SwingUtilities.invokeLater(d::dispose);});}catch(Exception e){problem.setText(e.getMessage());}}));d.setVisible(true);
    }catch(Exception e){error=e.getMessage();}}
    private void installTray(){if(!SystemTray.isSupported())return;try{PopupMenu menu=new PopupMenu();MenuItem show=new MenuItem("Uzelio"),exit=new MenuItem("Выйти");show.addActionListener(e->SwingUtilities.invokeLater(()->{frame.setVisible(true);frame.toFront();}));exit.addActionListener(e->SwingUtilities.invokeLater(this::exit));menu.add(show);menu.add(exit);tray=new TrayIcon(DesktopMain.icon(),"Uzelio",menu);tray.setImageAutoSize(true);SystemTray.getSystemTray().add(tray);org.uzel.desktop.messenger.DesktopMessenger.useNotifier((name,text)->{if(tray!=null&&frame!=null&&!frame.isActive())tray.displayMessage(name,text,TrayIcon.MessageType.INFO);});}catch(AWTException ignored){tray=null;}}
    private void exit(){timer.stop();Thread closing=new Thread(()->{backend.close();if(tray!=null)SystemTray.getSystemTray().remove(tray);System.exit(0);},"uzelio-close");closing.setDaemon(true);closing.start();}
    void renderPreview(Path directory)throws Exception {previewRendering=true;Files.createDirectories(directory);for(int[] size:new int[][]{{520,870},{450,670},{1000,780}}){setSize(size[0],size[1]);for(int next:new int[]{0,1,2,3,4}){showTab(next);layoutTree(this);BufferedImage image=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();printAll(g);g.dispose();String suffix=size[0]==450?"-min":size[0]==1000?"-wide":"";ImageIO.write(image,"png",directory.resolve(new String[]{"home","map","traffic","profile","chats"}[next]+suffix+".png").toFile());}}timer.stop();if(messengerPanel!=null)messengerPanel.close();worker.shutdownNow();connectionControl.shutdownNow();connectionWorker.shutdownNow();observer.shutdownNow();iconWorker.shutdownNow();discoveryWorker.shutdownNow();}
    private static void layoutTree(Container container){container.doLayout();for(Component c:container.getComponents())if(c instanceof Container child)layoutTree(child);}
}
