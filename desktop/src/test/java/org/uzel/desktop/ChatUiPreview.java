package org.uzel.desktop;

import org.uzel.desktop.messenger.MessengerPanel;
import org.uzel.messenger.chat.ChatRepository;
import javax.swing.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;

/** Isolated rendering fixture. Never opens storage, sockets, or a real user profile. */
public final class ChatUiPreview {
    public static void main(String[] args)throws Exception {
        Path out=Path.of(args[0]);Files.createDirectories(out);
        DesktopBackend backend=(DesktopBackend)Proxy.newProxyInstance(ChatUiPreview.class.getClassLoader(),new Class<?>[]{DesktopBackend.class},(proxy,method,values)->switch(method.getName()){
            case "snapshot"->new DesktopBackend.Snapshot(false,0,0,0,false,"");
            case "traffic"->new DesktopTrafficService.Snapshot(false,false,false,0,0,0,0,0,0,"","OFF");
            case "devices","selectedApplications"->List.of();
            case "routingMode"->DesktopRoutingMode.SELECTED_APPLICATIONS;
            case "routingAvailability"->new WindowsAppRouter.Availability(true,false,"");
            case "routingState"->new WindowsAppRouter.Snapshot(false,"OFF","");
            default->null;
        });
        SwingUtilities.invokeAndWait(()->{try{
            DesktopWindow window=new DesktopWindow(out,backend);window.renderPreview(out.resolve("empty"));
            MessengerPanel panel=(MessengerPanel)get(window,"messengerPanel");
            set(panel,"cached",List.of(new ChatRepository.Contact("fixture-alex","Алексей Иванов","fixture-no-invite"),new ChatRepository.Contact("fixture-maria","Мария Петрова","fixture-no-invite")));
            set(panel,"selected","fixture-alex");((JLabel)get(panel,"heading")).setText("Алексей Иванов");((JLabel)get(panel,"activeAvatar")).setText("АИ");
            invoke(panel,"renderContacts",null,null);
            invoke(panel,"renderHistory",List.class,List.of(new ChatRepository.Message("fixture1","fixture-alex","Привет! Давай встретимся у озера в субботу?",false,"received",1_000),new ChatRepository.Message("fixture2","fixture-alex","Давай. Напишу, когда буду рядом.",true,"delivered",2_000),new ChatRepository.Message("fixture3","fixture-alex","Тогда до встречи!",true,"queued",3_000)));
            for(int[] size:new int[][]{{1000,780},{450,670}}){window.setSize(size[0],size[1]);window.showTab(4);layout(window);BufferedImage image=new BufferedImage(size[0],size[1]+24,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();g.setColor(new Color(0x081524));g.fillRect(0,0,image.getWidth(),image.getHeight());g.setColor(Color.WHITE);g.setFont(new Font("Segoe UI",Font.PLAIN,11));g.drawString("Изолированные тестовые данные · без хранения и сети",12,16);g.translate(0,24);window.printAll(g);g.dispose();ImageIO.write(image,"png",out.resolve(size[0]>700?"chats-fixture-wide.png":"chats-fixture-mobile.png").toFile());}
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private static Object get(Object o,String key)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(o);}
    private static void set(Object o,String key,Object value)throws Exception{Field f=o.getClass().getDeclaredField(key);f.setAccessible(true);f.set(o,value);}
    private static void invoke(Object o,String key,Class<?> type,Object value)throws Exception{Method m=type==null?o.getClass().getDeclaredMethod(key):o.getClass().getDeclaredMethod(key,type);m.setAccessible(true);if(type==null)m.invoke(o);else m.invoke(o,value);}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container container)layout(container);}
}
