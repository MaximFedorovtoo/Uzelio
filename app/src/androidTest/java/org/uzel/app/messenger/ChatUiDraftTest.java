package org.uzel.app.messenger;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import android.widget.EditText;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.lang.reflect.*;
import java.util.List;
import org.uzel.app.MainActivity;
import org.uzel.messenger.chat.ChatRepository;

public final class ChatUiDraftTest extends InstrumentationTestCase {
    public void testDraftSurvivesHistoryRefreshAndSavedActivityState()throws Exception {
        Intent intent=new Intent(getInstrumentation().getTargetContext(),MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=getInstrumentation().startActivitySync(intent);
        try{getInstrumentation().runOnMainSync(()->{MessengerScreen screen=new MessengerScreen(activity,null);MessengerScreen restored=null;try{
            Field selected=MessengerScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(screen,"contact");
            EditText draft=(EditText)field(screen,"draft");draft.setText("Новый черновик");
            Method render=MessengerScreen.class.getDeclaredMethod("renderHistory",List.class);render.setAccessible(true);
            render.invoke(screen,List.of(new ChatRepository.Message("m1","contact","Сообщение",true,"queued",1)));
            assertSame(draft,field(screen,"draft"));assertEquals("Новый черновик",draft.getText().toString());
            assertTrue(contents(screen).contains(activity.getString(org.uzel.app.R.string.chat_queued)));
            Bundle state=new Bundle();screen.saveState(state);restored=new MessengerScreen(activity,state);
            assertEquals("Новый черновик",((EditText)field(restored,"draft")).getText().toString());
        }catch(Exception e){throw new AssertionError(e);}finally{screen.close();if(restored!=null)restored.close();}});}finally{getInstrumentation().runOnMainSync(activity::finish);}
    }
    public void testNativeThreeItemNavigationAndChatControls()throws Exception {
        Intent intent=new Intent(getInstrumentation().getTargetContext(),MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Activity activity=getInstrumentation().startActivitySync(intent);
        try{getInstrumentation().runOnMainSync(()->{try{
            android.widget.LinearLayout nav=(android.widget.LinearLayout)field(activity,"nav");assertEquals(3,nav.getChildCount());
            assertEquals(activity.getString(org.uzel.app.R.string.chat_title),nav.getChildAt(1).getContentDescription());
            nav.getChildAt(1).performClick();
            MessengerScreen screen=(MessengerScreen)field(activity,"messengerScreen");assertTrue(screen.isShown());
            assertTrue(descriptions(screen).contains(activity.getString(org.uzel.app.R.string.chat_add)));
            assertTrue(contents(screen).contains(activity.getString(org.uzel.app.R.string.chat_invite)));
            assertFalse(contents(screen).contains("Анна Ковалева"));
        }catch(Exception e){throw new AssertionError(e);}});}finally{getInstrumentation().runOnMainSync(activity::finish);}
    }
    public void testDestroyedScreenDiscardsQueuedAndLateUiCallbacks()throws Exception {
        Intent intent=new Intent(getInstrumentation().getTargetContext(),MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Activity activity=getInstrumentation().startActivitySync(intent);java.util.concurrent.atomic.AtomicBoolean ran=new java.util.concurrent.atomic.AtomicBoolean();
        try{getInstrumentation().runOnMainSync(()->{MessengerScreen screen=new MessengerScreen(activity,null);try{Method post=MessengerScreen.class.getDeclaredMethod("postUi",Runnable.class);post.setAccessible(true);post.invoke(screen,(Runnable)()->ran.set(true));screen.close();post.invoke(screen,(Runnable)()->ran.set(true));Method submit=MessengerScreen.class.getDeclaredMethod("submit",Runnable.class);submit.setAccessible(true);submit.invoke(screen,(Runnable)()->ran.set(true));}catch(Exception e){throw new AssertionError(e);}});getInstrumentation().waitForIdleSync();assertFalse(ran.get());}finally{getInstrumentation().runOnMainSync(activity::finish);}
    }
    public void testRenderIsolatedConversationPreview()throws Exception {
        Intent intent=new Intent(getInstrumentation().getTargetContext(),MainActivity.class);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);Activity activity=getInstrumentation().startActivitySync(intent);
        java.util.concurrent.atomic.AtomicReference<android.graphics.Bitmap> bitmap=new java.util.concurrent.atomic.AtomicReference<>();
        try{getInstrumentation().runOnMainSync(()->{MessengerScreen screen=new MessengerScreen(activity,null);try{
            Field selected=MessengerScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.set(screen,"fixture-contact");((TextView)field(screen,"heading")).setText("Алексей Иванов");((TextView)field(screen,"activeAvatar")).setText("АИ");((TextView)field(screen,"status")).setVisibility(View.GONE);
            Method show=MessengerScreen.class.getDeclaredMethod("showConversation");show.setAccessible(true);show.invoke(screen);
            Method render=MessengerScreen.class.getDeclaredMethod("renderHistory",List.class);render.setAccessible(true);render.invoke(screen,List.of(new ChatRepository.Message("f1","fixture-contact","Привет! Давай встретимся у озера в субботу?",false,"received",1000),new ChatRepository.Message("f2","fixture-contact","Давай. Напишу, когда буду рядом.",true,"delivered",2000),new ChatRepository.Message("f3","fixture-contact","Тогда до встречи!",true,"queued",3000)));
            screen.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1000,View.MeasureSpec.EXACTLY));screen.layout(0,0,720,1000);
            android.graphics.Bitmap image=android.graphics.Bitmap.createBitmap(720,1032,android.graphics.Bitmap.Config.ARGB_8888);android.graphics.Canvas canvas=new android.graphics.Canvas(image);canvas.drawColor(0xff081524);android.graphics.Paint paint=new android.graphics.Paint(3);paint.setColor(0xffedf7ff);paint.setTextSize(18);canvas.drawText("Изолированные тестовые данные · без хранения и сети",12,23,paint);canvas.translate(0,32);screen.draw(canvas);bitmap.set(image);
        }catch(Exception e){throw new AssertionError(e);}finally{screen.close();}});
        java.io.File file=new java.io.File(getInstrumentation().getTargetContext().getExternalFilesDir(null),"chat-ui-fixture.png");try(var output=new java.io.FileOutputStream(file)){assertTrue(bitmap.get().compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}bitmap.get().recycle();assertTrue(file.length()>0);
        }finally{getInstrumentation().runOnMainSync(activity::finish);}
    }
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static String descriptions(View view){StringBuilder text=new StringBuilder();if(view.getContentDescription()!=null)text.append(view.getContentDescription());if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)text.append(descriptions(group.getChildAt(i)));return text.toString();}
    private static String contents(View view){StringBuilder text=new StringBuilder();if(view instanceof TextView t)text.append(t.getText());if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++)text.append(contents(group.getChildAt(i)));return text.toString();}
}
