package org.uzel.app.messenger;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.*;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import org.uzel.app.R;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatInvite;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.LocalAddress;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;

/** A persistent native composer; history refresh never replaces the input view. */
public final class MessengerScreen extends LinearLayout implements AutoCloseable {
    private static final int BG=0xff081524,SURFACE=0xff122a40,TEXT=0xffedf7ff,MUTED=0xffa5bbcf,CYAN=0xff28e2dd;
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-chat-ui");t.setDaemon(true);return t;});
    private final LinearLayout list,thread,messages,listTools;
    private final ScrollView contactScroll;
    private final EditText search,draft;
    private final TextView status,heading,activeAvatar;
    private final Button invitationButton;
    private final Button send,retry,attach,voice;
    public static final int REQUEST_FILE=73;
    public static final int REQUEST_AUDIO=74;
    private static final int MAX_FILE=8_388_608;
    private static final int VOICE_LIMIT_MS=120_000;
    private MediaRecorder recorder;
    private File voiceFile;
    private String voiceContact="",attachmentContact="";
    private MediaPlayer player;
    private String playingId="";
    private File audioFile;
    private final Runnable voiceWatchdog=()->{if(recorder!=null)finishRecording(true);};
    private final HashMap<String,String> drafts=new HashMap<>();
    private java.util.List<ChatRepository.Contact> contacts=java.util.List.of();
    private ChatService service;
    private String selected="",signature="";
    private boolean pending;
    private volatile boolean closed;
    public MessengerScreen(Activity activity,Bundle saved){super(activity);this.activity=activity;setOrientation(VERTICAL);setBackgroundColor(BG);setPadding(dp(4),dp(6),dp(4),dp(4));
        if(saved!=null){selected=saved.getString("chat.selected","");Bundle all=saved.getBundle("chat.drafts");if(all!=null)for(String k:all.keySet())drafts.put(k,all.getString(k,""));}
        listTools=column();LinearLayout actions=row();TextView sectionTitle=text(R.string.chat_title,24,TEXT);actions.addView(sectionTitle,new LayoutParams(0,-2,1));Button add=button(R.string.chat_add,()->addContact()),invite=button(R.string.chat_invite,()->invitation());add.setText("+");add.setContentDescription(getResources().getString(R.string.chat_add));actions.addView(add,new LayoutParams(dp(48),dp(48)));listTools.addView(actions);invitationButton=invite;
        status=text(R.string.chat_loading,13,MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);addView(status);retry=button(R.string.chat_retry,()->{if(service==null){AndroidMessenger.chats(activity);refresh();}else work(()->service.retryNow());});addView(retry);retry.setVisibility(GONE);
        list=column();search=input(R.string.chat_search);search.setSingleLine(true);listTools.addView(search);list.addView(listTools);contactScroll=new ScrollView(activity);contactScroll.setFillViewport(true);contactScroll.addView(list);addView(contactScroll,new LayoutParams(-1,0,1));
        thread=column();LinearLayout title=row();Button back=button(R.string.chat_back,()->backToContacts());back.setText("‹");back.setTextSize(26);back.setContentDescription(getResources().getString(R.string.chat_back));title.addView(back,new LayoutParams(dp(48),dp(48)));activeAvatar=new TextView(activity);activeAvatar.setTextColor(TEXT);activeAvatar.setTextSize(16);activeAvatar.setGravity(Gravity.CENTER);activeAvatar.setTypeface(null,Typeface.BOLD);activeAvatar.setBackground(surface(0xff247675));LayoutParams avatarSize=new LayoutParams(dp(36),dp(36));avatarSize.setMarginEnd(dp(8));title.addView(activeAvatar,avatarSize);heading=text(R.string.chat_select,18,TEXT);title.addView(heading,new LayoutParams(0,-2,1));thread.addView(title);thread.addView(text(R.string.chat_private,12,MUTED));messages=column();ScrollView history=new ScrollView(activity);history.setFillViewport(true);history.addView(messages);thread.addView(history,new LayoutParams(-1,0,1));
        LinearLayout composer=row();draft=input(R.string.chat_message);draft.setMinLines(1);draft.setMaxLines(4);composer.addView(draft,new LayoutParams(0,-2,1));
        attach=button(R.string.chat_attach,this::pickFile);attach.setText("📎");attach.setTextSize(18);attach.setContentDescription(getResources().getString(R.string.chat_attach));
        LayoutParams attachParams=new LayoutParams(dp(44),dp(44));attachParams.setMarginStart(dp(4));composer.addView(attach,attachParams);
        voice=button(R.string.chat_voice_record,this::toggleVoice);voice.setText("🎤");voice.setTextSize(18);voice.setContentDescription(getResources().getString(R.string.chat_voice_record));
        LayoutParams voiceParams=new LayoutParams(dp(44),dp(44));voiceParams.setMarginStart(dp(4));composer.addView(voice,voiceParams);
        send=button(R.string.chat_send,()->send());send.setText("➤");send.setContentDescription(getResources().getString(R.string.chat_send));GradientDrawable primary=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff58f3e9,0xff1cc7d2});primary.setCornerRadius(dp(12));send.setBackground(primary);send.setTextColor(BG);composer.addView(send,new LayoutParams(dp(48),dp(48)));thread.addView(composer);thread.addView(text(R.string.chat_waiting,12,MUTED));addView(thread,new LayoutParams(-1,0,1));
        draft.addTextChangedListener(watch(()->{drafts.put(selected,draft.getText().toString());send.setEnabled(service!=null&&!selected.isEmpty()&&!draft.getText().toString().trim().isEmpty());}));search.addTextChangedListener(watch(this::renderContacts));draft.setText(drafts.getOrDefault(selected,""));send.setEnabled(false);showConversation();renderContacts();
    }
    public boolean backToContacts(){if(selected.isEmpty())return false;if(recorder!=null)finishRecording(false);stopPlayback();drafts.put(selected,draft.getText().toString());selected="";showConversation();return true;}
    public void saveState(Bundle out){drafts.put(selected,draft.getText().toString());out.putString("chat.selected",selected);Bundle all=new Bundle();for(var entry:drafts.entrySet())all.putString(entry.getKey(),entry.getValue());out.putBundle("chat.drafts",all);}
    private TextWatcher watch(Runnable r){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){r.run();}public void afterTextChanged(Editable e){}};}
    private void showConversation(){boolean open=!selected.isEmpty();contactScroll.setVisibility(open?GONE:VISIBLE);thread.setVisibility(open?VISIBLE:GONE);if(open)draft.setText(drafts.getOrDefault(selected,""));updateComposer();}
    public void refresh(){if(closed||pending)return;pending=true;String id=selected;submit(()->{try{if(service==null)service=AndroidMessenger.chats(activity).get();var next=service.contacts();var history=id.isEmpty()?java.util.List.<ChatRepository.Message>of():service.history(id);postUi(()->{if(closed)return;pending=false;contacts=next;for(var c:contacts)if(c.id().equals(selected)){heading.setText(c.name());activeAvatar.setText(initials(c.name()));}if(recorder==null)status.setVisibility(GONE);retry.setVisibility(GONE);renderContacts();if(selected.equals(id))renderHistory(history);send.setEnabled(!selected.isEmpty()&&!draft.getText().toString().trim().isEmpty());updateComposer();});}catch(Exception e){postUi(()->{pending=false;status.setVisibility(VISIBLE);status.setText(R.string.chat_error);retry.setVisibility(VISIBLE);});}});}
    private void renderContacts(){while(list.getChildCount()>1)list.removeViewAt(1);String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);int count=0;for(var c:contacts){if(!c.name().toLowerCase(Locale.ROOT).contains(q))continue;count++;LinearLayout item=row();item.setPadding(dp(10),dp(14),dp(10),dp(14));item.setBackground(surface(SURFACE));item.setElevation(dp(2));TextView avatar=new TextView(activity);avatar.setText(initials(c.name()));avatar.setTextColor(TEXT);avatar.setTextSize(16);avatar.setTypeface(null,Typeface.BOLD);avatar.setGravity(Gravity.CENTER);avatar.setBackground(surface(0xff247675));item.addView(avatar,new LayoutParams(dp(46),dp(46)));LinearLayout copy=column();copy.setPadding(dp(12),0,0,0);TextView name=new TextView(activity);name.setText(c.name());name.setTextColor(TEXT);name.setTextSize(14);name.setTypeface(null,Typeface.BOLD);copy.addView(name);copy.addView(text(R.string.chat_personal,12,MUTED));item.addView(copy,new LayoutParams(0,-2,1));item.setContentDescription(c.name());item.setFocusable(true);item.setOnClickListener(v->{selected=c.id();heading.setText(c.name());activeAvatar.setText(initials(c.name()));signature="";showConversation();refresh();});LayoutParams lp=new LayoutParams(-1,-2);lp.topMargin=dp(8);list.addView(item,lp);}if(count==0)list.addView(text(q.isEmpty()?R.string.chat_empty:R.string.chat_not_found,16,MUTED));View spacer=new View(activity);list.addView(spacer,new LayoutParams(-1,0,1));TextView footer=text(R.string.chat_storage,12,MUTED);footer.setPadding(dp(14),dp(18),dp(14),dp(14));footer.setBackground(surface(SURFACE));LayoutParams lp=new LayoutParams(-1,-2);lp.topMargin=dp(18);list.addView(footer,lp);list.addView(invitationButton,new LayoutParams(-1,-2));}
    private static String initials(String name){StringBuilder b=new StringBuilder();for(String part:name.trim().split("\\s+")){if(!part.isEmpty())b.appendCodePoint(part.codePointAt(0));if(b.codePointCount(0,b.length())==2)break;}return b.toString().toUpperCase(Locale.ROOT);}
    private void renderHistory(java.util.List<ChatRepository.Message> history){
        String next=history.toString();if(signature.equals(next))return;signature=next;messages.removeAllViews();
        if(history.isEmpty())messages.addView(text(R.string.chat_history_empty,15,MUTED));
        for(var m:history){
            LinearLayout line=row();line.setGravity(m.outgoing()?Gravity.END:Gravity.START);TextView bubble=new TextView(activity);bubble.setTextColor(TEXT);bubble.setTextSize(16);
            String metadata=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(m.timeMillis()))+(m.outgoing()?" · "+getResources().getString(switch(m.status()){case "delivered"->R.string.chat_delivered;case "sent"->R.string.chat_sent;default->R.string.chat_queued;}):"");
            ChatRepository.Attachment a=m.attachment();
            String first=a==null?m.text():a.name();
            String second;
            if(a==null)second=metadata;
            else{
                int action=a.mime()!=null&&a.mime().startsWith("audio/")?R.string.chat_play:R.string.chat_save;
                second=getResources().getString(action)+" · "+Formatter.formatFileSize(activity,a.size())+" · "+metadata;
                bubble.setOnClickListener(v->openAttachment(m));
                bubble.setFocusable(true);
                bubble.setContentDescription(a.name()+", "+second);
            }
            android.text.SpannableString value=new android.text.SpannableString(first+"\n"+second);int start=first.length()+1;
            value.setSpan(new android.text.style.AbsoluteSizeSpan(11,true),start,value.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            value.setSpan(new android.text.style.ForegroundColorSpan(0xff9abac9),start,value.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            value.setSpan(new android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_OPPOSITE),start,value.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);bubble.setText(value);
            bubble.setPadding(dp(14),dp(10),dp(14),dp(10));bubble.setBackground(surface(m.outgoing()?0xff16505c:SURFACE));bubble.setMaxWidth(dp(285));LayoutParams lp=new LayoutParams(-2,-2);lp.topMargin=dp(8);lp.bottomMargin=dp(2);line.addView(bubble,lp);messages.addView(line);
        }
    }
    private void send(){if(closed)return;String value=draft.getText().toString().trim(),id=selected;if(value.isEmpty()||service==null||id.isEmpty())return;send.setEnabled(false);submit(()->{try{service.send(id,value);postUi(()->{if(selected.equals(id)&&draft.getText().toString().trim().equals(value)){draft.setText("");drafts.put(id,"");}else if(drafts.getOrDefault(id,"").trim().equals(value))drafts.put(id,"");signature="";refresh();});}catch(Exception e){postUi(()->{status.setVisibility(VISIBLE);status.setText(R.string.chat_error);retry.setVisibility(VISIBLE);send.setEnabled(true);});}});}
    private void updateComposer(){boolean ready=service!=null&&!selected.isEmpty();attach.setEnabled(ready&&recorder==null);voice.setEnabled(ready||recorder!=null);}
    private void pickFile(){if(closed||recorder!=null||service==null||selected.isEmpty())return;attachmentContact=selected;Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("*/*");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{activity.startActivityForResult(intent,REQUEST_FILE);}catch(Exception e){attachmentContact="";fail(R.string.chat_file_unreadable);}}
    public void onActivityResult(int request,int result,Intent data){if(request!=REQUEST_FILE)return;String contact=attachmentContact;attachmentContact="";if(result!=Activity.RESULT_OK||data==null||data.getData()==null||closed)return;String target=contact.isEmpty()?selected:contact;if(target.isEmpty())return;sendPicked(data.getData(),target);}
    private void sendPicked(Uri uri,String contact){
        submit(()->{
            String name="",mime="application/octet-stream";byte[] data=null;
            try{
                name=displayName(uri);mime=mimeOf(uri);
                long declared=declaredSize(uri);if(declared>MAX_FILE)throw new AttachmentTooLarge();
                data=readUri(uri,MAX_FILE);
            }catch(AttachmentTooLarge tooLarge){postUi(()->fail(R.string.chat_file_too_large));return;}
            catch(Exception e){postUi(()->fail(R.string.chat_file_unreadable));return;}
            if(data==null||data.length<1){postUi(()->fail(R.string.chat_file_empty));return;}
            try{
                if(service==null)service=AndroidMessenger.chats(activity).get();
                service.sendFile(contact,name,mime,data);
                postUi(()->{signature="";refresh();});
            }catch(Exception e){postUi(()->fail(R.string.chat_action_error));}
        });
    }
    private String displayName(Uri uri){
        String name=null;
        try(Cursor cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){int column=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);if(column>=0&&!cursor.isNull(column))name=cursor.getString(column);}
        }catch(Exception ignored){/* Provider may not expose a display name. */}
        if(name==null||name.isBlank())name=uri.getLastPathSegment();
        return sanitize(name,getResources().getString(R.string.chat_file_fallback));
    }
    private long declaredSize(Uri uri){
        try(Cursor cursor=activity.getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){int column=cursor.getColumnIndex(OpenableColumns.SIZE);if(column>=0&&!cursor.isNull(column))return cursor.getLong(column);}
        }catch(Exception ignored){/* Unknown size is checked while reading instead. */}
        return -1;
    }
    private String mimeOf(Uri uri){try{return normalizeMime(activity.getContentResolver().getType(uri));}catch(Exception ignored){return "application/octet-stream";}}
    private static String normalizeMime(String mime){
        if(mime!=null){int separator=mime.indexOf(';');String base=(separator>=0?mime.substring(0,separator):mime).trim().toLowerCase(Locale.ROOT);if(base.matches("[a-z0-9.+-]{1,100}/[a-z0-9.+-]{1,100}"))return base;}
        return "application/octet-stream";
    }
    private byte[] readUri(Uri uri,int max)throws Exception{try(InputStream in=activity.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("Provider returned no stream");return readLimited(in,max);}}
    private static byte[] readLimited(InputStream in,int max)throws IOException,AttachmentTooLarge{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int total=0,read;
        while((read=in.read(buffer))!=-1){total+=read;if(total>max)throw new AttachmentTooLarge();out.write(buffer,0,read);}
        return out.toByteArray();
    }
    private static byte[] readFile(File file,int max)throws Exception{try(InputStream in=new FileInputStream(file)){return readLimited(in,max);}}
    private static String sanitize(String name,String fallback){
        String value=name==null?"":name.trim().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_").trim();
        while(value.startsWith("."))value=value.substring(1);
        if(value.isEmpty())value=fallback;
        if(value.length()>120){int dot=value.lastIndexOf('.');String extension=dot>0&&value.length()-dot<=16?value.substring(dot):"";value=value.substring(0,Math.max(1,120-extension.length()))+extension;}
        return value;
    }
    private void openAttachment(ChatRepository.Message message){
        ChatRepository.Attachment attachment=message.attachment();if(attachment==null)return;
        if(attachment.mime()!=null&&attachment.mime().startsWith("audio/"))toggleAudio(message);else saveAttachment(message);
    }
    private void toggleAudio(ChatRepository.Message message){
        if(message.id().equals(playingId)){stopPlayback();return;}
        stopPlayback();final String id=message.id();
        submit(()->{
            try{
                if(service==null)service=AndroidMessenger.chats(activity).get();
                byte[] data=service.attachment(id);if(data==null||data.length<1)throw new IOException("Attachment is unavailable");
                File file=File.createTempFile("uzel-audio-",".m4a",activity.getCacheDir());
                try(OutputStream out=new FileOutputStream(file)){out.write(data);}
                postUi(()->playAudio(id,file));
            }catch(Exception e){postUi(()->fail(R.string.chat_play_failed));}
        });
    }
    private void playAudio(String id,File file){
        if(closed){file.delete();return;}
        stopPlayback();MediaPlayer next=new MediaPlayer();
        try{next.setDataSource(file.getAbsolutePath());next.prepare();next.start();}
        catch(Exception e){try{next.release();}catch(RuntimeException ignored){}file.delete();fail(R.string.chat_play_failed);return;}
        player=next;playingId=id;audioFile=file;
        next.setOnCompletionListener(done->stopPlayback());
    }
    private void stopPlayback(){
        MediaPlayer current=player;player=null;playingId="";
        if(current!=null){try{current.stop();}catch(RuntimeException ignored){}try{current.release();}catch(RuntimeException ignored){}}
        if(audioFile!=null){audioFile.delete();audioFile=null;}
    }
    private void saveAttachment(ChatRepository.Message message){
        final ChatRepository.Attachment attachment=message.attachment();if(attachment==null)return;final String id=message.id();
        submit(()->{
            try{
                if(service==null)service=AndroidMessenger.chats(activity).get();
                byte[] data=service.attachment(id);if(data==null||data.length<1)throw new IOException("Attachment is unavailable");
                saveToDownloads(attachment,data);
                postUi(()->Toast.makeText(activity,R.string.chat_saved,Toast.LENGTH_LONG).show());
            }catch(Exception e){postUi(()->fail(R.string.chat_save_failed));}
        });
    }
    private void saveToDownloads(ChatRepository.Attachment attachment,byte[] data)throws Exception{
        String name=sanitize(attachment.name(),getResources().getString(R.string.chat_file_fallback));
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,normalizeMime(attachment.mime()));
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/Uzelio");
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=activity.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null)throw new IOException("Downloads collection rejected the file");
        boolean published=false;
        try{
            try(OutputStream out=activity.getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("No output stream");out.write(data);out.flush();}
            ContentValues done=new ContentValues();done.put(MediaStore.MediaColumns.IS_PENDING,0);
            if(activity.getContentResolver().update(uri,done,null,null)<1)throw new IOException("Could not publish the file");
            published=true;
        }finally{if(!published)try{activity.getContentResolver().delete(uri,null,null);}catch(RuntimeException ignored){/* Best-effort cleanup of the unfinished row. */}}
    }
    private void toggleVoice(){
        if(closed)return;
        if(recorder!=null){finishRecording(false);return;}
        if(service==null||selected.isEmpty())return;
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_AUDIO);return;}
        startRecording();
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grantResults){
        if(request!=REQUEST_AUDIO)return;
        if(grantResults!=null&&grantResults.length>0&&grantResults[0]==PackageManager.PERMISSION_GRANTED)startRecording();
        else fail(R.string.chat_mic_denied);
    }
    private void startRecording(){
        if(closed||recorder!=null||service==null||selected.isEmpty())return;
        File file=null;MediaRecorder next=null;
        try{
            file=new File(activity.getCacheDir(),"uzel-voice-"+System.currentTimeMillis()+".m4a");
            next=Build.VERSION.SDK_INT>=Build.VERSION_CODES.S?new MediaRecorder(activity):new MediaRecorder();
            next.setAudioSource(MediaRecorder.AudioSource.MIC);
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            next.setAudioEncodingBitRate(64000);
            next.setAudioSamplingRate(44100);
            next.setMaxDuration(VOICE_LIMIT_MS);
            next.setOutputFile(file.getAbsolutePath());
            next.setOnInfoListener((mediaRecorder,what,extra)->{if(what==MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED)postUi(()->finishRecording(true));});
            next.prepare();next.start();
        }catch(Exception e){
            if(next!=null)try{next.release();}catch(RuntimeException ignored){/* Recorder was never started. */}
            if(file!=null)file.delete();
            fail(R.string.chat_voice_failed);return;
        }
        recorder=next;voiceFile=file;voiceContact=selected;
        handler.removeCallbacks(voiceWatchdog);handler.postDelayed(voiceWatchdog,VOICE_LIMIT_MS+1000);
        voice.setText("■");voice.setContentDescription(getResources().getString(R.string.chat_voice_stop));
        status.setVisibility(VISIBLE);status.setText(R.string.chat_voice_recording);
    }
    private void finishRecording(boolean limitReached){
        MediaRecorder current=recorder;if(current==null)return;
        recorder=null;handler.removeCallbacks(voiceWatchdog);
        try{current.stop();}catch(RuntimeException ignored){/* Auto-stop already finalized the file. */}
        try{current.reset();}catch(RuntimeException ignored){}
        try{current.release();}catch(RuntimeException ignored){}
        voice.setText("🎤");voice.setContentDescription(getResources().getString(R.string.chat_voice_record));
        status.setVisibility(GONE);
        File file=voiceFile;voiceFile=null;String contact=voiceContact;voiceContact="";
        if(file==null||!file.exists()||file.length()<1){if(file!=null)file.delete();fail(R.string.chat_voice_failed);updateComposer();return;}
        if(limitReached)Toast.makeText(activity,R.string.chat_voice_limit,Toast.LENGTH_LONG).show();
        if(closed||contact.isEmpty()){file.delete();updateComposer();return;}
        String name=getResources().getString(R.string.chat_voice_name)+" "+new SimpleDateFormat("yyyy-MM-dd HH.mm.ss",Locale.ROOT).format(new Date(file.lastModified()))+".m4a";
        submit(()->{
            try{
                if(service==null)service=AndroidMessenger.chats(activity).get();
                byte[] data=readFile(file,MAX_FILE);if(data.length<1)throw new IOException("Empty recording");
                service.sendFile(contact,name,"audio/mp4",data);
                postUi(()->{signature="";refresh();});
            }catch(Exception e){postUi(()->fail(R.string.chat_voice_failed));}
            finally{file.delete();}
        });
        updateComposer();
    }
    private void fail(int message){if(closed)return;status.setVisibility(VISIBLE);status.setText(message);Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
    private static final class AttachmentTooLarge extends Exception{}
    private void addContact(){LinearLayout form=column();form.setPadding(dp(20),dp(10),dp(20),dp(10));EditText code=input(R.string.chat_code),name=input(R.string.chat_name);code.setMinLines(2);code.setMaxLines(3);name.setSingleLine(true);form.addView(code);form.addView(name);form.addView(text(R.string.chat_verify_notice,14,MUTED));AlertDialog d=new AlertDialog.Builder(activity).setTitle(R.string.chat_add).setView(form).setNegativeButton(R.string.chat_cancel,null).setPositiveButton(R.string.chat_add,null).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{String c=code.getText().toString().trim(),n=name.getText().toString().trim();if(c.isEmpty()||n.isEmpty()){name.setError(getResources().getString(R.string.chat_name));return;}work(()->{String safety=ChatInvite.decode(c).safetyCode();postUi(()->new AlertDialog.Builder(activity).setTitle(R.string.chat_verify_title).setMessage(getResources().getString(R.string.chat_safety,safety)+"\n\n"+getResources().getString(R.string.chat_verify_notice)).setNegativeButton(R.string.chat_cancel,null).setPositiveButton(R.string.chat_match,(a,b)->work(()->{service.addContact(c,n);postUi(d::dismiss);})).show());});}));d.show();}
    private void invitation(){LinearLayout form=column();form.setPadding(dp(20),dp(10),dp(20),dp(10));form.addView(text(R.string.chat_host,15,TEXT));EditText host=input(R.string.chat_host_hint);host.setSingleLine(true);host.setText(LocalAddress.preferred());form.addView(host);EditText relay=input(R.string.chat_relay_hint);relay.setSingleLine(true);form.addView(text(R.string.chat_relay,13,MUTED));form.addView(relay);TextView port=text(R.string.chat_loading,13,MUTED);if(service!=null)port.setText(getResources().getString(R.string.chat_port,service.localPort()));form.addView(port);form.addView(text(R.string.chat_invite_notice,14,MUTED));AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.chat_invite).setView(form).setNegativeButton(R.string.chat_cancel,null).setPositiveButton(R.string.chat_generate,null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{String advertisedHost=host.getText().toString().trim();String relayValue=relay.getText().toString().trim();work(()->{String code=service.invitation(advertisedHost,relayValue);String safety=ChatInvite.decode(code).safetyCode();postUi(()->{dialog.dismiss();TextView value=new TextView(activity);value.setText(getResources().getString(R.string.chat_safety,safety)+"\n\n"+code);value.setTextIsSelectable(true);value.setPadding(dp(20),dp(10),dp(20),dp(10));ScrollView codeScroll=new ScrollView(activity);codeScroll.addView(value);FrameLayout codeHolder=new FrameLayout(activity);codeHolder.addView(codeScroll,new FrameLayout.LayoutParams(-1,dp(280)));new AlertDialog.Builder(activity).setTitle(R.string.chat_invite).setView(codeHolder).setNegativeButton(R.string.chat_cancel,null).setPositiveButton(R.string.chat_copy,(a,b)->{((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Uzelio",code));Toast.makeText(activity,R.string.chat_copied,Toast.LENGTH_SHORT).show();}).show();});});}));dialog.show();}
    private void postUi(Runnable action){if(closed)return;handler.post(()->{if(!closed&&!activity.isFinishing()&&!activity.isDestroyed())action.run();});}
    private void submit(Runnable action){if(closed)return;try{worker.submit(action);}catch(RejectedExecutionException ignored){/* Activity has closed; no UI work may outlive it. */}}
    private interface Action{void run()throws Exception;}
    private void work(Action action){if(closed)return;submit(()->{try{if(service==null)service=AndroidMessenger.chats(activity).get();action.run();postUi(this::refresh);}catch(Exception e){postUi(()->{status.setVisibility(VISIBLE);status.setText(R.string.chat_action_error);retry.setVisibility(VISIBLE);Toast.makeText(activity,R.string.chat_action_error,Toast.LENGTH_LONG).show();});}});}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(int id,int size,int color){TextView v=new TextView(activity);v.setText(id);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));return v;}
    private EditText input(int hint){EditText v=new EditText(activity);v.setTextColor(TEXT);v.setHintTextColor(MUTED);v.setHint(hint);v.setTextSize(16);v.setPadding(dp(12),dp(8),dp(12),dp(8));v.setBackground(surface(SURFACE));v.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);return v;}
    private Button button(int title,Runnable action){Button b=new Button(activity){@Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:.5f);}};b.setText(title);styleButton(b);b.setOnClickListener(v->action.run());return b;}
    private void styleButton(Button b){b.setTextColor(CYAN);b.setTextSize(13);b.setAllCaps(false);b.setMinHeight(dp(48));b.setBackground(surface(SURFACE));b.setElevation(dp(2));}
    private GradientDrawable surface(int color){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{color,color==SURFACE?0xff19374e:color});d.setCornerRadius(dp(16));d.setStroke(dp(1),0xff36546c);return d;}
    @Override public void close(){closed=true;handler.removeCallbacks(voiceWatchdog);MediaRecorder current=recorder;recorder=null;if(current!=null){try{current.stop();}catch(RuntimeException ignored){}try{current.release();}catch(RuntimeException ignored){}}voiceContact="";attachmentContact="";if(voiceFile!=null){voiceFile.delete();voiceFile=null;}stopPlayback();handler.removeCallbacksAndMessages(null);worker.shutdownNow();}
}
