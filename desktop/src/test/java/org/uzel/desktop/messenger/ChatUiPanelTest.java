package org.uzel.desktop.messenger;

import org.junit.Test;
import org.uzel.messenger.chat.ChatRepository;
import javax.swing.*;
import java.awt.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;
import static org.junit.Assert.*;

public class ChatUiPanelTest {
    @Test public void historyRefreshPreservesComposerAndDisplaysOnlyServiceDeliveryState()throws Exception {
        SwingUtilities.invokeAndWait(()->{MessengerPanel panel=new MessengerPanel(Path.of("unused-ui-preview"));try{
            JTextArea composer=(JTextArea)field(panel,"draft");composer.setText("Неотправленный черновик");
            Method history=MessengerPanel.class.getDeclaredMethod("renderHistory",List.class);history.setAccessible(true);
            history.invoke(panel,List.of(new ChatRepository.Message("m1","c1","Реальное сообщение",true,"queued",1234)));
            assertSame(composer,field(panel,"draft"));assertEquals("Неотправленный черновик",composer.getText());
            assertTrue(contents(panel).contains("Ожидает отправки"));assertFalse(contents(panel).contains("Прочитано"));
            history.invoke(panel,List.of(new ChatRepository.Message("m1","c1","Реальное сообщение",true,"delivered",1234)));
            assertTrue(contents(panel).contains("Доставлено"));assertFalse(contents(panel).contains("Ожидает отправки"));
            assertEquals("Неотправленный черновик",composer.getText());
        }catch(Exception e){throw new RuntimeException(e);}finally{panel.close();}});
    }
    @Test public void localSearchShowsHonestEmptyResultAndNoSyntheticContacts()throws Exception {
        SwingUtilities.invokeAndWait(()->{MessengerPanel panel=new MessengerPanel(Path.of("unused-ui-preview"));try{
            assertTrue(contents(panel).contains("Пока нет чатов"));
            Field cache=MessengerPanel.class.getDeclaredField("cached");cache.setAccessible(true);cache.set(panel,List.of(new ChatRepository.Contact("c1","Контакт А","invite")));
            JTextField search=(JTextField)field(panel,"search");search.setText("никого");
            assertTrue(contents(panel).contains("Контакты не найдены"));
            search.setText("Контакт");assertTrue(contents(panel).contains("Контакт А"));
        }catch(Exception e){throw new RuntimeException(e);}finally{panel.close();}});
    }
    @Test public void queuedUiCallbacksAreDiscardedAfterClose()throws Exception {
        java.util.concurrent.atomic.AtomicBoolean ran=new java.util.concurrent.atomic.AtomicBoolean();
        SwingUtilities.invokeAndWait(()->{MessengerPanel panel=new MessengerPanel(Path.of("unused-ui-preview"));try{Method post=MessengerPanel.class.getDeclaredMethod("postUi",Runnable.class);post.setAccessible(true);post.invoke(panel,(Runnable)()->ran.set(true));panel.close();post.invoke(panel,(Runnable)()->ran.set(true));Method work=MessengerPanel.class.getDeclaredMethod("submit",Runnable.class);work.setAccessible(true);work.invoke(panel,(Runnable)()->ran.set(true));}catch(Exception e){throw new AssertionError(e);}});
        SwingUtilities.invokeAndWait(()->{});assertFalse("Closed panels must discard already queued and late callbacks",ran.get());
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static String contents(Component component){StringBuilder s=new StringBuilder();if(component instanceof JLabel l)s.append(l.getText());if(component instanceof AbstractButton b)s.append(b.getText());if(component instanceof javax.swing.text.JTextComponent t)s.append(t.getText());if(component instanceof Container c)for(Component child:c.getComponents())s.append(contents(child));return s.toString();}
}
