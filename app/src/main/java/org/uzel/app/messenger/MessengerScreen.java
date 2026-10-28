package org.uzel.app.messenger;

import android.Manifest;
import android.animation.ValueAnimator;
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
import android.provider.Settings;
import android.text.*;
import android.text.format.Formatter;
import android.view.*;
import android.widget.*;
import org.uzel.app.R;
import org.uzel.messenger.chat.ChatService;
import org.uzel.messenger.chat.ChatInvite;
import org.uzel.messenger.chat.ChatRepository;
import org.uzel.messenger.chat.ChatWire;
import org.uzel.messenger.chat.LocalAddress;
import org.webrtc.EglBase;
import org.webrtc.RendererCommon;
import org.webrtc.SurfaceViewRenderer;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.text.SimpleDateFormat;

/** A persistent native composer; history refresh never replaces the input view. */
public final class MessengerScreen extends LinearLayout implements AutoCloseable {
    private static final int VOID=0xFF04090F,BG=0xFF070E17,BG2=0xFF0A1420,SURFACE=0xFF0E1E2E,SURFACE2=0xFF122639,RAISED=0xFF163149,
            TEXT=0xFFEAF6FF,MUTED=0xFF93ADC4,MUTED2=0xFF6F8BA3,ACCENT=0xFF38F0DD,ACCENT_DEEP=0xFF1CC7D2,
            MINT=0xFF7CF6C8,IRIS=0xFF8F7CFF,AMBER=0xFFFFC978,CORAL=0xFFFF8E9A,
            LINE=0x241DBEDE,LINE_STRONG=0x421DBEDE,GLASS=0xA00E1E2E,CYAN=ACCENT;
    private static final int[] AVATAR_TONES={ACCENT,MINT,IRIS,AMBER};
    private final Activity activity;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"uzelio-chat-ui");t.setDaemon(true);return t;});
    private final LinearLayout list,thread,messages,listTools;
    private final ScrollView contactScroll;
    private final EditText search,draft;
    private final TextView status,heading,activeAvatar;
    private final Button invitationButton;
    private final Button retry;
    private final View send;
    private final View attach,voice;
    private final View callButton,videoButton;
    private final Button callAccept,callReject,callSwitch,callEnd;
    private final TextView callState,callHint;
    private final LinearLayout callPanel,callActions;
    private final FrameLayout callVideoStage;
    private final AndroidMessenger.CallHandler callHandler=this::onCallFrame;
    private final CallSession.Listener callListener=new CallSession.Listener(){
        @Override public void onCallState(CallSession session,CallSession.State state){MessengerScreen.this.onCallState(session,state);}
        @Override public void onCallVideo(CallSession session,boolean sending,String notice){MessengerScreen.this.onCallVideo(session,sending,notice);}
    };
    private CallSession call;
    private SurfaceViewRenderer localVideo,remoteVideo;
    private EglBase videoEgl;
    private CallSession videoSession;
    private boolean videoUnavailable;
    private boolean pendingCallAccept;
    private String pendingCallContact="";
    private boolean pendingCallVideo;
    public static final int REQUEST_FILE=73;
    public static final int REQUEST_AUDIO=74;
    public static final int REQUEST_CALL_AUDIO=75;
    public static final int REQUEST_CALL_VIDEO=76;
    private final Runnable hideFailedCall=()->{
        if(call!=null&&call.state()==CallSession.State.FAILED){call.close();call=null;renderCall();updateComposer();}
    };
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
    public MessengerScreen(Activity activity,Bundle saved){super(activity);this.activity=activity;setOrientation(VERTICAL);setBackground(screenBackground());setPadding(dp(10),dp(10),dp(10),dp(10));
        if(saved!=null){selected=saved.getString("chat.selected","");Bundle all=saved.getBundle("chat.drafts");if(all!=null)for(String k:all.keySet())drafts.put(k,all.getString(k,""));}
        listTools=column();LinearLayout actions=row();TextView sectionTitle=text(R.string.chat_title,24,TEXT);sectionTitle.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));sectionTitle.setLetterSpacing(-0.02f);actions.addView(sectionTitle,new LayoutParams(0,-2,1));Button add=button(R.string.chat_add,()->addContact()),invite=button(R.string.chat_invite,()->invitation());add.setText("+");add.setTextSize(22);add.setContentDescription(getResources().getString(R.string.chat_add));add.setBackground(circleSurface(true));actions.addView(add,new LayoutParams(dp(48),dp(48)));listTools.addView(actions);invitationButton=invite;
        status=text(R.string.chat_loading,12,MUTED);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setMaxLines(2);status.setEllipsize(TextUtils.TruncateAt.END);addView(status);retry=button(R.string.chat_retry,()->{if(service==null){AndroidMessenger.chats(activity);refresh();}else work(()->service.retryNow());});addView(retry);retry.setVisibility(GONE);
        list=column();search=input(R.string.chat_search);search.setSingleLine(true);search.setBackground(fieldPill());search.setPadding(dp(16),dp(10),dp(16),dp(10));listTools.addView(search);list.addView(listTools);contactScroll=new ScrollView(activity);contactScroll.setFillViewport(true);contactScroll.addView(list);addView(contactScroll,new LayoutParams(-1,0,1));
        thread=column();LinearLayout title=row();title.setPadding(0,0,0,dp(4));Button back=button(R.string.chat_back,()->backToContacts());back.setText("‹");back.setTextSize(26);back.setContentDescription(getResources().getString(R.string.chat_back));back.setBackground(circleSurface(false));title.addView(back,new LayoutParams(dp(44),dp(44)));activeAvatar=new TextView(activity);activeAvatar.setTextColor(ACCENT);activeAvatar.setTextSize(15);activeAvatar.setGravity(Gravity.CENTER);activeAvatar.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));activeAvatar.setBackground(avatarBackground(ACCENT));LayoutParams avatarSize=new LayoutParams(dp(38),dp(38));avatarSize.setMarginEnd(dp(10));title.addView(activeAvatar,avatarSize);heading=text(R.string.chat_select,18,TEXT);heading.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));heading.setSingleLine(true);heading.setEllipsize(TextUtils.TruncateAt.END);title.addView(heading,new LayoutParams(0,-2,1));callButton=iconControl(ChatIcons.PHONE,MINT,dp(40),this::startCall,"Позвонить");LayoutParams callIconParams=new LayoutParams(dp(40),dp(40));callIconParams.setMarginStart(dp(6));title.addView(callButton,callIconParams);videoButton=iconControl(ChatIcons.VIDEO,IRIS,dp(40),this::startVideoCall,"Видеозвонок");LayoutParams videoIconParams=new LayoutParams(dp(40),dp(40));videoIconParams.setMarginStart(dp(4));title.addView(videoButton,videoIconParams);thread.addView(title);
        LinearLayout privateRow=row();privateRow.setPadding(dp(2),0,0,dp(6));View privateDot=led(MINT);LayoutParams privateDotParams=new LayoutParams(dp(6),dp(6));privateDotParams.setMarginEnd(dp(6));privateRow.addView(privateDot,privateDotParams);TextView privateLabel=text(R.string.chat_private,11,MUTED2);privateLabel.setSingleLine(true);privateLabel.setEllipsize(TextUtils.TruncateAt.END);privateRow.addView(privateLabel,new LayoutParams(0,-2,1));thread.addView(privateRow);
        callPanel=column();callPanel.setPadding(dp(14),dp(10),dp(14),dp(10));callPanel.setBackground(glassCard(20));callPanel.setVisibility(GONE);
        callState=text(R.string.chat_private,14,TEXT);callState.setText("");callState.setSingleLine(true);callState.setEllipsize(TextUtils.TruncateAt.END);callHint=text(R.string.chat_private,12,MUTED);callHint.setText("");callHint.setMaxLines(2);callHint.setEllipsize(TextUtils.TruncateAt.END);callHint.setVisibility(GONE);callActions=column();
        callAccept=button("Принять",this::acceptCall);callAccept.setBackground(tintPill(MINT));callAccept.setTextColor(MINT);callReject=button("Отклонить",this::rejectCall);callReject.setBackground(tintPill(CORAL));callReject.setTextColor(CORAL);callSwitch=button("Сменить камеру",this::switchCamera);callSwitch.setBackground(tintPill(ACCENT));callSwitch.setTextColor(ACCENT);callEnd=button("Завершить",this::endCall);callEnd.setBackground(tintPill(RAISED));callEnd.setTextColor(TEXT);
        for(Button action:new Button[]{callAccept,callReject,callSwitch,callEnd})action.setAutoSizeTextTypeUniformWithConfiguration(10,12,1,android.util.TypedValue.COMPLEX_UNIT_SP);
        LinearLayout callRow1=row(),callRow2=row();callRow2.setPadding(0,dp(6),0,0);callActions.addView(callRow1);callActions.addView(callRow2);
        callRow1.addView(callAccept,callActionParams(true));callRow1.addView(callReject,callActionParams(false));callRow2.addView(callSwitch,callActionParams(true));callRow2.addView(callEnd,callActionParams(false));
        callVideoStage=new FrameLayout(activity);callVideoStage.setBackground(rounded(0xFF04101A,16,LINE));callVideoStage.setVisibility(GONE);
        callPanel.addView(callVideoStage,new LayoutParams(-1,dp(220)));callPanel.addView(callState);callPanel.addView(callHint);callPanel.addView(callActions);thread.addView(callPanel,new LayoutParams(-1,-2));
        messages=column();messages.setPadding(0,dp(10),0,dp(6));ScrollView history=new ScrollView(activity);history.setFillViewport(true);history.setClipToPadding(false);history.addView(messages);thread.addView(history,new LayoutParams(-1,0,1));
        LinearLayout composer=row();composer.setGravity(Gravity.CENTER_VERTICAL);composer.setBackground(composerBackground());composer.setPadding(dp(4),dp(4),dp(4),dp(4));draft=input(R.string.chat_message);draft.setMinLines(1);draft.setMaxLines(4);draft.setBackground(null);draft.setPadding(dp(10),dp(8),dp(10),dp(8));composer.addView(draft,new LayoutParams(0,-2,1));
        attach=iconControl(ChatIcons.ATTACH,TEXT,dp(44),this::pickFile,activity.getString(R.string.chat_attach));attach.setBackground(circleSurface(false));LayoutParams attachParams=new LayoutParams(dp(44),dp(44));attachParams.setMarginStart(dp(2));composer.addView(attach,attachParams);
        voice=iconControl(ChatIcons.MIC,TEXT,dp(44),this::toggleVoice,activity.getString(R.string.chat_voice_record));voice.setBackground(circleSurface(false));LayoutParams voiceParams=new LayoutParams(dp(44),dp(44));voiceParams.setMarginStart(dp(2));composer.addView(voice,voiceParams);
        send=iconControl(ChatIcons.SEND,BG,dp(46),this::send,activity.getString(R.string.chat_send));send.setBackground(sendCircle());composer.addView(send,new LayoutParams(dp(46),dp(46)));
        LayoutParams composerParams=new LayoutParams(-1,-2);composerParams.topMargin=dp(8);thread.addView(composer,composerParams);
        TextView waiting=text(R.string.chat_waiting,11,MUTED2);waiting.setGravity(Gravity.CENTER);waiting.setSingleLine(true);waiting.setEllipsize(TextUtils.TruncateAt.END);waiting.setPadding(0,dp(6),0,dp(4));thread.addView(waiting);addView(thread,new LayoutParams(-1,0,1));
        draft.addTextChangedListener(watch(()->{drafts.put(selected,draft.getText().toString());send.setEnabled(service!=null&&!selected.isEmpty()&&!draft.getText().toString().trim().isEmpty());}));search.addTextChangedListener(watch(this::renderContacts));draft.setText(drafts.getOrDefault(selected,""));send.setEnabled(false);showConversation();renderContacts();AndroidMessenger.setCallHandler(callHandler);renderCall();
    }
    public boolean backToContacts(){if(selected.isEmpty())return false;if(call!=null)endCall();if(recorder!=null)finishRecording(false);stopPlayback();drafts.put(selected,draft.getText().toString());selected="";showConversation();return true;}
    public void saveState(Bundle out){drafts.put(selected,draft.getText().toString());out.putString("chat.selected",selected);Bundle all=new Bundle();for(var entry:drafts.entrySet())all.putString(entry.getKey(),entry.getValue());out.putBundle("chat.drafts",all);}
    private TextWatcher watch(Runnable r){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){r.run();}public void afterTextChanged(Editable e){}};}
    private void showConversation(){boolean open=!selected.isEmpty();contactScroll.setVisibility(open?GONE:VISIBLE);thread.setVisibility(open?VISIBLE:GONE);if(open)draft.setText(drafts.getOrDefault(selected,""));reveal(open?thread:contactScroll);updateComposer();}
    public void refresh(){if(closed||pending)return;pending=true;String id=selected;submit(()->{try{if(service==null)service=AndroidMessenger.chats(activity).get();var next=service.contacts();var history=id.isEmpty()?java.util.List.<ChatRepository.Message>of():service.history(id);postUi(()->{if(closed)return;pending=false;contacts=next;for(var c:contacts)if(c.id().equals(selected)){heading.setText(c.name());activeAvatar.setText(initials(c.name()));}if(recorder==null)status.setVisibility(GONE);retry.setVisibility(GONE);renderContacts();if(selected.equals(id))renderHistory(history);send.setEnabled(!selected.isEmpty()&&!draft.getText().toString().trim().isEmpty());updateComposer();});}catch(Exception e){postUi(()->{pending=false;status.setVisibility(VISIBLE);status.setText(R.string.chat_error);retry.setVisibility(VISIBLE);});}});}
    private void renderContacts(){while(list.getChildCount()>1)list.removeViewAt(1);String q=search.getText().toString().trim().toLowerCase(Locale.ROOT);int count=0;for(var c:contacts){if(!c.name().toLowerCase(Locale.ROOT).contains(q))continue;int tone=AVATAR_TONES[count%AVATAR_TONES.length];count++;LinearLayout item=row();item.setPadding(dp(10),dp(12),dp(12),dp(12));item.setBackground(contactRow());item.setElevation(dp(2));TextView avatar=new TextView(activity);avatar.setText(initials(c.name()));avatar.setTextColor(tone);avatar.setTextSize(16);avatar.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));avatar.setGravity(Gravity.CENTER);avatar.setBackground(avatarBackground(tone));item.addView(avatar,new LayoutParams(dp(44),dp(44)));LinearLayout copy=column();copy.setPadding(dp(12),0,0,0);TextView name=new TextView(activity);name.setText(c.name());name.setTextColor(TEXT);name.setTextSize(15);name.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));name.setSingleLine(true);name.setEllipsize(TextUtils.TruncateAt.END);copy.addView(name);TextView preview=text(R.string.chat_personal,12,MUTED2);preview.setPadding(0,dp(2),0,0);preview.setSingleLine(true);preview.setEllipsize(TextUtils.TruncateAt.END);copy.addView(preview);item.addView(copy,new LayoutParams(0,-2,1));item.setContentDescription(c.name());item.setFocusable(true);item.setOnClickListener(v->{press(v);if(call!=null&&!call.contactId().equals(c.id()))endCall();selected=c.id();heading.setText(c.name());activeAvatar.setText(initials(c.name()));signature="";showConversation();refresh();});item.setOnLongClickListener(v->{confirmDelete(c.id(),c.name());return true;});LayoutParams lp=new LayoutParams(-1,-2);lp.topMargin=dp(8);list.addView(item,lp);}if(count==0){TextView empty=text(q.isEmpty()?R.string.chat_empty:R.string.chat_not_found,15,MUTED2);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(20),dp(28),dp(20),dp(28));list.addView(empty);}View spacer=new View(activity);list.addView(spacer,new LayoutParams(-1,0,1));TextView footer=text(R.string.chat_storage,11,MUTED);footer.setPadding(dp(14),dp(14),dp(14),dp(14));footer.setBackground(privacyCard());footer.setLineSpacing(dp(2),1f);LayoutParams footerParams=new LayoutParams(-1,-2);footerParams.topMargin=dp(16);list.addView(footer,footerParams);LayoutParams inviteParams=new LayoutParams(-1,-2);inviteParams.topMargin=dp(8);list.addView(invitationButton,inviteParams);}
    /** Long press asks before removing the conversation with its files and queue. */
    private void confirmDelete(String id,String name){
        if(service==null)return;
        new android.app.AlertDialog.Builder(activity).setTitle(name)
            .setMessage(activity.getString(R.string.chat_delete_message))
            .setPositiveButton(activity.getString(R.string.chat_delete),(dialog,which)->work(()->{
                try{service.deleteContact(id);postUi(()->{if(selected.equals(id)){selected="";signature="";showConversation();}refresh();});}
                catch(Exception failed){postUi(()->fail(R.string.chat_error));}
            }))
            .setNegativeButton(android.R.string.cancel,null).show();
    }    private static String initials(String name){StringBuilder b=new StringBuilder();for(String part:name.trim().split("\\s+")){if(!part.isEmpty())b.appendCodePoint(part.codePointAt(0));if(b.codePointCount(0,b.length())==2)break;}return b.toString().toUpperCase(Locale.ROOT);}
    private void renderHistory(java.util.List<ChatRepository.Message> history){
        String next=history.toString();if(signature.equals(next))return;signature=next;messages.removeAllViews();
        if(history.isEmpty()){TextView empty=text(R.string.chat_history_empty,14,MUTED2);empty.setGravity(Gravity.CENTER);empty.setPadding(0,dp(28),0,dp(28));messages.addView(empty);return;}
        for(var m:history){
            LinearLayout line=row();line.setGravity(m.outgoing()?Gravity.END:Gravity.START);Bubble bubble=new Bubble(activity,dp(292));bubble.setPadding(dp(14),dp(10),dp(14),dp(9));bubble.setBackground(bubbleBackground(m.outgoing()));
            String time=new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date(m.timeMillis()));
            String state=getResources().getString(switch(m.status()){case "delivered"->R.string.chat_delivered;case "sent"->R.string.chat_sent;default->R.string.chat_queued;});
            String meta=m.outgoing()?time+" · "+("queued".equals(m.status())?state:"✓ "+state):time;
            ChatRepository.Attachment a=m.attachment();
            if(a==null){
                TextView body=new TextView(activity);body.setText(m.text());body.setTextColor(TEXT);body.setTextSize(15);body.setLineSpacing(dp(2),1f);bubble.addView(body);bubble.addView(stamp(meta));
            }else{
                int action=a.mime()!=null&&a.mime().startsWith("audio/")?R.string.chat_play:R.string.chat_save;String actionText=getResources().getString(action);
                String second=actionText+" · "+Formatter.formatFileSize(activity,a.size())+" · "+meta;
                LinearLayout card=row();card.setBackground(fileCard());card.setPadding(dp(8),dp(8),dp(8),dp(8));
                TextView icon=new TextView(activity);icon.setText(a.mime()!=null&&a.mime().startsWith("audio/")?"♪":"▤");icon.setTextSize(18);icon.setTextColor(ACCENT);icon.setGravity(Gravity.CENTER);icon.setBackground(fileIcon());card.addView(icon,new LayoutParams(dp(36),dp(36)));
                LinearLayout copy=column();copy.setPadding(dp(10),0,0,0);TextView fileName=new TextView(activity);fileName.setText(a.name());fileName.setTextColor(TEXT);fileName.setTextSize(13);fileName.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));fileName.setMaxLines(1);fileName.setEllipsize(android.text.TextUtils.TruncateAt.END);copy.addView(fileName);TextView fileMeta=new TextView(activity);fileMeta.setText(Formatter.formatFileSize(activity,a.size()));fileMeta.setTextColor(MUTED2);fileMeta.setTextSize(10);fileMeta.setSingleLine(true);copy.addView(fileMeta);card.addView(copy,new LayoutParams(0,-2,1));bubble.addView(card,new LayoutParams(-1,-2));
                TextView actionView=new TextView(activity);actionView.setText(actionText);actionView.setTextColor(ACCENT);actionView.setTextSize(11);actionView.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));actionView.setPadding(0,dp(6),0,0);bubble.addView(actionView);bubble.addView(stamp(meta));
                bubble.setOnClickListener(v->openAttachment(m));bubble.setFocusable(true);bubble.setContentDescription(a.name()+", "+second);
            }
            LayoutParams lp=new LayoutParams(-2,-2);lp.topMargin=dp(8);lp.bottomMargin=dp(2);line.addView(bubble,lp);messages.addView(line);
        }
    }
    private TextView stamp(String value){
        TextView stamp=new TextView(activity);stamp.setTextSize(11);stamp.setTextColor(MUTED2);stamp.setGravity(Gravity.END);stamp.setPadding(0,dp(3),0,0);
        android.text.SpannableString text=new android.text.SpannableString(value);int tick=value.indexOf('✓');
        if(tick>=0)text.setSpan(new android.text.style.ForegroundColorSpan(MINT),tick,tick+1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        stamp.setText(text);return stamp;
    }
    private static final class Bubble extends LinearLayout {
        private final int maximum;
        Bubble(Context context,int maximum){super(context);this.maximum=maximum;setOrientation(VERTICAL);}
        @Override protected void onMeasure(int widthMeasureSpec,int heightMeasureSpec){
            int parent=MeasureSpec.getSize(widthMeasureSpec);
            int width=MeasureSpec.makeMeasureSpec(Math.min(maximum,parent),MeasureSpec.AT_MOST);
            super.onMeasure(width,heightMeasureSpec);
        }
    }
    private void send(){if(closed)return;String value=draft.getText().toString().trim(),id=selected;if(value.isEmpty()||service==null||id.isEmpty())return;send.setEnabled(false);submit(()->{try{service.send(id,value);postUi(()->{if(selected.equals(id)&&draft.getText().toString().trim().equals(value)){draft.setText("");drafts.put(id,"");}else if(drafts.getOrDefault(id,"").trim().equals(value))drafts.put(id,"");signature="";refresh();});}catch(Exception e){postUi(()->{status.setVisibility(VISIBLE);status.setText(R.string.chat_error);retry.setVisibility(VISIBLE);send.setEnabled(true);});}});}
    private void updateComposer(){boolean ready=service!=null&&!selected.isEmpty();boolean inCall=call!=null&&call.isActive();attach.setEnabled(ready&&recorder==null&&!inCall);voice.setEnabled((ready||recorder!=null)&&!inCall);callButton.setEnabled(service!=null&&!selected.isEmpty()&&recorder==null&&!inCall);videoButton.setEnabled(service!=null&&!selected.isEmpty()&&recorder==null&&!inCall);}
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
        if(call!=null&&call.isActive())return;
        if(recorder!=null){finishRecording(false);return;}
        if(service==null||selected.isEmpty())return;
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_AUDIO);return;}
        startRecording();
    }
    public void onRequestPermissionsResult(int request,String[] permissions,int[] grantResults){
        if(request==REQUEST_CALL_AUDIO||request==REQUEST_CALL_VIDEO){
            boolean videoRequest=request==REQUEST_CALL_VIDEO;
            boolean audio=activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
            boolean camera=activity.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
            boolean accept=pendingCallAccept;pendingCallAccept=false;String contact=pendingCallContact;pendingCallContact="";
            boolean wantsVideo=pendingCallVideo||videoRequest;pendingCallVideo=false;
            if(!audio){
                if(call!=null&&call.state()==CallSession.State.RINGING)call.reject();
                failText("Для звонка нужен доступ к микрофону.");
                return;
            }
            if(accept&&call!=null&&call.state()==CallSession.State.RINGING){
                if(call.isVideo()&&!camera)failText("Нет доступа к камере — звонок продолжится со звуком.");
                call.accept();renderCall();updateComposer();
                return;
            }
            if(contact.isEmpty())return;
            if(wantsVideo&&!camera){failText("Для видеозвонка нужен доступ к камере. Аудиозвонок доступен.");return;}
            beginCall(contact,wantsVideo&&camera);
            return;
        }
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
        voice.setContentDescription(getResources().getString(R.string.chat_voice_stop));voice.setBackground(circleSurface(true));
        status.setVisibility(VISIBLE);status.setText(R.string.chat_voice_recording);
    }
    private void finishRecording(boolean limitReached){
        MediaRecorder current=recorder;if(current==null)return;
        recorder=null;handler.removeCallbacks(voiceWatchdog);
        try{current.stop();}catch(RuntimeException ignored){/* Auto-stop already finalized the file. */}
        try{current.reset();}catch(RuntimeException ignored){}
        try{current.release();}catch(RuntimeException ignored){}
        voice.setContentDescription(getResources().getString(R.string.chat_voice_record));voice.setBackground(circleSurface(false));
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
    private void startCall(){
        if(closed||service==null||selected.isEmpty())return;
        if(call!=null&&call.isActive())return;
        if(recorder!=null)finishRecording(false);
        if(activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            pendingCallContact=selected;pendingCallAccept=false;pendingCallVideo=false;
            activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_CALL_AUDIO);
            return;
        }
        beginCall(selected,false);
    }
    private void startVideoCall(){
        if(closed||service==null||selected.isEmpty())return;
        if(call!=null&&call.isActive())return;
        if(recorder!=null)finishRecording(false);
        boolean audio=activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        boolean camera=activity.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
        if(!audio||!camera){
            pendingCallContact=selected;pendingCallAccept=false;pendingCallVideo=true;
            ArrayList<String> missing=new ArrayList<>();
            if(!audio)missing.add(Manifest.permission.RECORD_AUDIO);
            if(!camera)missing.add(Manifest.permission.CAMERA);
            activity.requestPermissions(missing.toArray(new String[0]),REQUEST_CALL_VIDEO);
            return;
        }
        beginCall(selected,true);
    }
    private void beginCall(String contact,boolean video){
        if(closed||service==null||contact==null||contact.isEmpty())return;
        if(call!=null){call.close();call=null;}
        handler.removeCallbacks(hideFailedCall);
        videoUnavailable=false;callHint.setText("");callHint.setVisibility(GONE);
        CallSession session=new CallSession(activity.getApplicationContext(),service,callListener);
        call=session;
        try{if(video)session.startVideo(contact);else session.start(contact);}
        catch(RuntimeException failure){session.close();call=null;renderCall();updateComposer();failText("Не удалось начать звонок.");return;}
        renderCall();updateComposer();
    }
    private void acceptCall(){
        CallSession session=call;
        if(closed||session==null||session.state()!=CallSession.State.RINGING)return;
        boolean audio=activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED;
        boolean video=session.isVideo();
        boolean camera=activity.checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;
        if(!audio||(video&&!camera)){
            pendingCallAccept=true;pendingCallContact="";pendingCallVideo=video;
            ArrayList<String> missing=new ArrayList<>();
            if(!audio)missing.add(Manifest.permission.RECORD_AUDIO);
            if(video&&!camera)missing.add(Manifest.permission.CAMERA);
            activity.requestPermissions(missing.toArray(new String[0]),video?REQUEST_CALL_VIDEO:REQUEST_CALL_AUDIO);
            return;
        }
        session.accept();renderCall();updateComposer();
    }
    private void switchCamera(){
        CallSession session=call;
        if(closed||session==null||!session.isSendingVideo())return;
        session.switchCamera();
    }
    private void rejectCall(){CallSession session=call;if(session!=null)session.reject();}
    private void endCall(){
        CallSession session=call;
        if(session==null)return;
        if(session.state()==CallSession.State.FAILED){
            handler.removeCallbacks(hideFailedCall);session.close();call=null;renderCall();updateComposer();return;
        }
        session.hangup();
    }
    private void onCallFrame(String contactId,int kind,String callId,String sdp){
        postUi(()->{if(!closed)handleCallFrame(contactId,kind,callId,sdp);});
    }
    private void handleCallFrame(String contactId,int kind,String callId,String sdp){
        if(kind==ChatWire.CALL_OFFER){incomingOffer(contactId,callId,sdp);return;}
        CallSession session=call;
        if(session==null||!session.contactId().equals(contactId))return;
        if(kind==ChatWire.CALL_ANSWER)session.answer(callId,sdp);
        else if(kind==ChatWire.CALL_END)session.end(callId);
    }
    private void incomingOffer(String contactId,String callId,String sdp){
        if(service==null)return;
        CallSession current=call;
        if(current!=null&&current.isActive()){rejectBusy(contactId,callId);return;}
        if(current!=null){current.close();call=null;}
        handler.removeCallbacks(hideFailedCall);
        if(recorder!=null)finishRecording(false);
        selected=contactId;String name=contactName(contactId);heading.setText(name);activeAvatar.setText(initials(name));signature="";showConversation();refresh();
        videoUnavailable=false;callHint.setText("");callHint.setVisibility(GONE);
        CallSession session=new CallSession(activity.getApplicationContext(),service,callListener);
        call=session;renderCall();updateComposer();
        try{session.incoming(contactId,callId,sdp);}
        catch(RuntimeException failure){session.close();call=null;renderCall();updateComposer();}
        renderCall();
    }
    private String contactName(String id){for(var c:contacts)if(c.id().equals(id))return c.name();return "Uzelio";}
    private void rejectBusy(String contactId,String callId){
        submit(()->{try{if(service==null)service=AndroidMessenger.chats(activity).get();service.sendCall(contactId,ChatWire.CALL_END,callId,"");}catch(Exception ignored){/* The caller times out. */}});
    }
    private void onCallState(CallSession session,CallSession.State state){
        if(closed||session!=call)return;
        handler.removeCallbacks(hideFailedCall);
        if(state==CallSession.State.ENDED){call=null;videoUnavailable=false;}
        renderCall();updateComposer();
        if(state==CallSession.State.FAILED)handler.postDelayed(hideFailedCall,6000);
    }
    private void onCallVideo(CallSession session,boolean sending,String notice){
        if(closed||session!=call)return;
        if(notice!=null&&!notice.isEmpty()){callHint.setText(notice);callHint.setVisibility(VISIBLE);}
        if(sending){
            if(notice==null||notice.isEmpty()){callHint.setText("");callHint.setVisibility(GONE);}
        }else{
            videoUnavailable=true;
            releaseVideoUi();
        }
        renderCall();
    }
    private void renderCall(){
        CallSession session=call;
        if(session==null||session.state()==CallSession.State.ENDED){hideCallPanel();return;}
        boolean video=session.isVideo()&&!videoUnavailable&&session.state()!=CallSession.State.FAILED;
        if(video)ensureVideoUi(session);else releaseVideoUi();
        CallSession.State state=session.state();
        boolean entering=callPanel.getVisibility()!=VISIBLE;
        callPanel.setVisibility(VISIBLE);callAccept.setVisibility(GONE);callReject.setVisibility(GONE);callSwitch.setVisibility(GONE);callEnd.setVisibility(GONE);
        callVideoStage.setVisibility(video?VISIBLE:GONE);
        callSwitch.setVisibility(video&&session.isSendingVideo()?VISIBLE:GONE);
        if(entering)reveal(callPanel);
        if(state==CallSession.State.CALLING){
            callState.setText(video?"Видеозвонок…":"Звоним…");callEnd.setText("Завершить");callEnd.setVisibility(VISIBLE);
        }else if(state==CallSession.State.RINGING){
            if(session.isAccepted()){callState.setText(video?"Видеосоединение…":"Соединение…");callEnd.setText("Завершить");callEnd.setVisibility(VISIBLE);}
            else{callState.setText(video?"Входящий видеозвонок":"Входящий звонок");callAccept.setVisibility(VISIBLE);callReject.setVisibility(VISIBLE);}
        }else if(state==CallSession.State.CONNECTED){
            callState.setText(video?"Видео в сети":"В сети");callEnd.setText("Завершить");callEnd.setVisibility(VISIBLE);
        }else if(state==CallSession.State.FAILED){
            callState.setText("Не удалось");callEnd.setText("Скрыть");callEnd.setVisibility(VISIBLE);
        }else callPanel.setVisibility(GONE);
    }
    private void hideCallPanel(){
        callPanel.setVisibility(GONE);callAccept.setVisibility(GONE);callReject.setVisibility(GONE);callSwitch.setVisibility(GONE);callEnd.setVisibility(GONE);
        callVideoStage.setVisibility(GONE);releaseVideoUi();
    }
    private void ensureVideoUi(CallSession session){
        if(session==null)return;
        if(videoSession==session&&localVideo!=null&&remoteVideo!=null)return;
        releaseVideoUi();
        SurfaceViewRenderer remote=null,local=null;
        EglBase egl=null;
        try{
            egl=EglBase.create();
            remote=new SurfaceViewRenderer(activity);
            remote.init(egl.getEglBaseContext(),null);
            remote.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            remote.setEnableHardwareScaler(true);
            remote.setMirror(false);
            local=new SurfaceViewRenderer(activity);
            local.init(egl.getEglBaseContext(),null);
            local.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL);
            local.setEnableHardwareScaler(true);
            local.setMirror(true);
            local.setZOrderMediaOverlay(true);
            callVideoStage.addView(remote,new FrameLayout.LayoutParams(-1,-1));
            FrameLayout.LayoutParams preview=new FrameLayout.LayoutParams(dp(84),dp(112),Gravity.TOP|Gravity.END);
            preview.setMargins(0,dp(8),dp(8),0);
            callVideoStage.addView(local,preview);
            videoEgl=egl;remoteVideo=remote;localVideo=local;videoSession=session;
            session.attachVideoSinks(local,remote);
        }catch(RuntimeException|LinkageError failure){
            if(local!=null)try{local.release();}catch(RuntimeException ignored){}
            if(remote!=null)try{remote.release();}catch(RuntimeException ignored){}
            if(egl!=null)try{egl.release();}catch(RuntimeException ignored){}
            callVideoStage.removeAllViews();
            videoUnavailable=true;
            callHint.setText("Видео недоступно — звонок со звуком.");callHint.setVisibility(VISIBLE);
        }
    }
    private void releaseVideoUi(){
        CallSession session=videoSession;videoSession=null;
        if(session!=null)try{session.attachVideoSinks(null,null);}catch(RuntimeException ignored){}
        callVideoStage.removeAllViews();
        SurfaceViewRenderer remote=remoteVideo;remoteVideo=null;
        SurfaceViewRenderer local=localVideo;localVideo=null;
        if(remote!=null)try{remote.release();}catch(RuntimeException ignored){}
        if(local!=null)try{local.release();}catch(RuntimeException ignored){}
        EglBase egl=videoEgl;videoEgl=null;
        if(egl!=null)try{egl.release();}catch(RuntimeException ignored){}
    }
    private LayoutParams callActionParams(boolean gap){LayoutParams params=new LayoutParams(0,dp(44),1);if(gap)params.setMarginEnd(dp(6));return params;}
    private void failText(String message){if(closed)return;status.setVisibility(VISIBLE);status.setText(message);Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
    private void fail(int message){if(closed)return;status.setVisibility(VISIBLE);status.setText(message);Toast.makeText(activity,message,Toast.LENGTH_LONG).show();}
    private static final class AttachmentTooLarge extends Exception{}
    public void openAddContact(String code){addContact(code==null?"":code);}
    private void addContact(){addContact("");}
    private void addContact(String prefill){LinearLayout form=column();form.setPadding(dp(20),dp(10),dp(20),dp(10));EditText code=input(R.string.chat_code),name=input(R.string.chat_name);code.setMinLines(2);code.setMaxLines(3);name.setSingleLine(true);String clip=prefill==null||prefill.isEmpty()?clipboardCode():prefill;if(!clip.isEmpty())code.setText(clip);form.addView(code);Button paste=button(R.string.chat_paste,()->{String value=clipboardCode();if(!value.isEmpty())code.setText(value);});paste.setBackground(tintPill(ACCENT));paste.setTextColor(ACCENT);form.addView(paste);form.addView(name);form.addView(text(R.string.chat_verify_notice,14,MUTED));AlertDialog d=new AlertDialog.Builder(activity).setTitle(R.string.chat_add).setView(form).setNegativeButton(R.string.chat_cancel,null).setPositiveButton(R.string.chat_add,null).create();d.setOnShowListener(v->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{String c=code.getText().toString().trim(),n=name.getText().toString().trim();if(c.isEmpty()||n.isEmpty()){name.setError(getResources().getString(R.string.chat_name));return;}work(()->{String safety=ChatInvite.decode(c).safetyCode();postUi(()->new AlertDialog.Builder(activity).setTitle(R.string.chat_verify_title).setMessage(getResources().getString(R.string.chat_safety,safety)+"\n\n"+getResources().getString(R.string.chat_verify_notice)).setNegativeButton(R.string.chat_cancel,null).setPositiveButton(R.string.chat_match,(a,b)->work(()->{service.addContact(c,n);postUi(d::dismiss);})).show());});}));d.show();}
    private void invitation(){LinearLayout form=column();form.setPadding(dp(20),dp(10),dp(20),dp(10));form.addView(text(R.string.chat_host,15,TEXT));EditText host=input(R.string.chat_host_hint);host.setSingleLine(true);host.setText(LocalAddress.preferred());form.addView(host);EditText relay=input(R.string.chat_relay_hint);relay.setSingleLine(true);form.addView(text(R.string.chat_relay,13,MUTED));form.addView(relay);TextView port=text(R.string.chat_loading,13,MUTED);if(service!=null)port.setText(getResources().getString(R.string.chat_port,service.localPort()));form.addView(port);form.addView(text(R.string.chat_invite_notice,14,MUTED));AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.chat_invite).setView(form).setNegativeButton(R.string.chat_cancel,null).setPositiveButton(R.string.chat_generate,null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{String advertisedHost=host.getText().toString().trim();String relayValue=relay.getText().toString().trim();work(()->{String code=service.invitation(advertisedHost,relayValue);String safety=ChatInvite.decode(code).safetyCode();postUi(()->{dialog.dismiss();TextView value=new TextView(activity);value.setText(getResources().getString(R.string.chat_safety,safety)+"\n\n"+code);value.setTextIsSelectable(true);value.setPadding(dp(20),dp(10),dp(20),dp(10));ScrollView codeScroll=new ScrollView(activity);codeScroll.addView(value);FrameLayout codeHolder=new FrameLayout(activity);codeHolder.addView(codeScroll,new FrameLayout.LayoutParams(-1,dp(280)));new AlertDialog.Builder(activity).setTitle(R.string.chat_invite).setView(codeHolder).setNegativeButton(R.string.chat_cancel,null).setNeutralButton(R.string.chat_share,(a,b)->shareInvite(code)).setPositiveButton(R.string.chat_copy,(a,b)->{((android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Uzelio",code));Toast.makeText(activity,R.string.chat_copied,Toast.LENGTH_SHORT).show();}).show();});});}));dialog.show();}
    private String clipboardCode(){
        try{
            android.content.ClipboardManager clipboard=(android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip=clipboard==null?null:clipboard.getPrimaryClip();
            if(clip==null||clip.getItemCount()==0)return "";
            CharSequence text=clip.getItemAt(0).coerceToText(activity);
            String value=text==null?null:text.toString().trim();
            return value!=null&&value.startsWith("uzelio-chat:")?value:"";
        }catch(RuntimeException ignored){return "";}
    }
    private void shareInvite(String code){
        Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,code);
        try{activity.startActivity(Intent.createChooser(send,activity.getString(R.string.chat_share)));}
        catch(RuntimeException ignored){Toast.makeText(activity,R.string.chat_share,Toast.LENGTH_SHORT).show();}
    }    private void postUi(Runnable action){if(closed)return;handler.post(()->{if(!closed&&!activity.isFinishing()&&!activity.isDestroyed())action.run();});}
    private void submit(Runnable action){if(closed)return;try{worker.submit(action);}catch(RejectedExecutionException ignored){/* Activity has closed; no UI work may outlive it. */}}
    private interface Action{void run()throws Exception;}
    private void work(Action action){if(closed)return;submit(()->{try{if(service==null)service=AndroidMessenger.chats(activity).get();action.run();postUi(this::refresh);}catch(Exception e){postUi(()->{status.setVisibility(VISIBLE);status.setText(R.string.chat_action_error);retry.setVisibility(VISIBLE);Toast.makeText(activity,R.string.chat_action_error,Toast.LENGTH_LONG).show();});}});}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private static int withAlpha(int color,int alpha){return (color&0x00ffffff)|(alpha<<24);}
    private boolean motionEnabled(){try{return Settings.Global.getFloat(activity.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)!=0f;}catch(RuntimeException ignored){return true;}}
    private void press(View view){if(view==null||!motionEnabled())return;ValueAnimator press=ValueAnimator.ofFloat(.96f,1f);press.setDuration(150);press.setInterpolator(new android.view.animation.DecelerateInterpolator());press.addUpdateListener(a->{float scale=(float)a.getAnimatedValue();view.setScaleX(scale);view.setScaleY(scale);});press.start();}
    private void reveal(View view){if(view==null)return;if(!motionEnabled()||!view.isAttachedToWindow()){view.setAlpha(1f);view.setTranslationY(0f);return;}view.setAlpha(0f);view.setTranslationY(dp(12));ValueAnimator appear=ValueAnimator.ofFloat(0f,1f);appear.setDuration(380);appear.setInterpolator(new android.view.animation.DecelerateInterpolator());appear.addUpdateListener(a->{float value=(float)a.getAnimatedValue();view.setAlpha(value);view.setTranslationY((1f-value)*dp(12));});appear.start();}
    private LinearLayout column(){LinearLayout v=new LinearLayout(activity);v.setOrientation(VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(activity);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private TextView text(int id,int size,int color){TextView v=new TextView(activity);v.setText(id);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));return v;}
    private View led(int tone){View v=new View(activity);v.setBackground(dot(tone));return v;}
    private EditText input(int hint){EditText v=new EditText(activity);v.setTextColor(TEXT);v.setHintTextColor(MUTED2);v.setHint(hint);v.setTextSize(15);v.setPadding(dp(14),dp(10),dp(14),dp(10));v.setBackground(fieldSurface());v.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);return v;}
    private Button button(int title,Runnable action){return button(getResources().getString(title),action);}
    private View iconControl(int kind,int color,int size,Runnable action,String description){
        View control=ChatIcons.view(activity,kind,color);
        control.setLayoutParams(new LayoutParams(size,size));
        control.setContentDescription(description);
        control.setFocusable(true);
        control.setOnClickListener(v->{press(v);action.run();});
        return control;
    }
    /** Opens a conversation directly; used by intents and screenshot checks. */
    public void openContact(String id){
        if(id==null||id.isEmpty())return;
        selected=id;showConversation();refresh();
    }    private Button button(String title,Runnable action){Button b=new Button(activity){@Override public void setEnabled(boolean enabled){super.setEnabled(enabled);setAlpha(enabled?1f:.5f);}};b.setText(title);styleButton(b);b.setOnClickListener(v->{press(v);action.run();});return b;}
    private void styleButton(Button b){b.setTextColor(ACCENT);b.setTextSize(12);b.setAllCaps(false);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.BOLD));b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setSingleLine(true);b.setMaxLines(1);b.setEllipsize(TextUtils.TruncateAt.END);b.setGravity(Gravity.CENTER);b.setBackground(secondaryPill());b.setElevation(0);b.setPadding(dp(14),dp(8),dp(14),dp(8));}
    private GradientDrawable rounded(int color,int radius,int strokeColor){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(strokeColor!=0)d.setStroke(Math.max(1,dp(1)),strokeColor);return d;}
    private GradientDrawable circleSurface(boolean accent){GradientDrawable d=new GradientDrawable();d.setColor(accent?withAlpha(ACCENT,0x22):withAlpha(TEXT,0x08));d.setCornerRadius(dp(999));d.setStroke(Math.max(1,dp(1)),accent?withAlpha(ACCENT,0x55):LINE);return d;}
    private GradientDrawable tintPill(int tone){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{withAlpha(tone,0x22),withAlpha(tone,0x0C)});d.setCornerRadius(dp(999));d.setStroke(Math.max(1,dp(1)),withAlpha(tone,0x66));return d;}
    private GradientDrawable secondaryPill(){return rounded(0xC0122639,999,LINE_STRONG);}
    private GradientDrawable fieldSurface(){return rounded(SURFACE2,18,LINE);}
    private GradientDrawable fieldPill(){return rounded(0xC0122639,999,LINE);}
    private GradientDrawable screenBackground(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xA00A1420,0xA0070E17});d.setCornerRadius(dp(28));d.setStroke(Math.max(1,dp(1)),LINE);return d;}
    private GradientDrawable glassCard(int radius){return rounded(GLASS,radius,LINE);}
    private GradientDrawable avatarBackground(int tone){return rounded(withAlpha(tone,0x26),14,withAlpha(tone,0x66));}
    private GradientDrawable contactRow(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xC0122639,0xB00E1E2E});d.setCornerRadius(dp(18));d.setStroke(Math.max(1,dp(1)),LINE);return d;}
    private GradientDrawable bubbleBackground(boolean outgoing){GradientDrawable d=new GradientDrawable();d.setColor(outgoing?withAlpha(ACCENT,0x29):SURFACE);float r=dp(16),small=dp(6);d.setCornerRadii(outgoing?new float[]{r,r,r,r,small,small,r,r}:new float[]{r,r,r,r,r,r,small,small});d.setStroke(Math.max(1,dp(1)),outgoing?withAlpha(ACCENT,0x66):LINE);return d;}
    private GradientDrawable fileCard(){return rounded(0x7306101A,12,LINE);}
    private GradientDrawable fileIcon(){return rounded(withAlpha(ACCENT,0x18),10,withAlpha(ACCENT,0x4D));}
    private GradientDrawable composerBackground(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xCC122639,0xCC0E1E2E});d.setCornerRadius(dp(999));d.setStroke(Math.max(1,dp(1)),LINE);return d;}
    private GradientDrawable sendCircle(){GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xFF8BFBD4,ACCENT,ACCENT_DEEP});d.setCornerRadius(dp(999));return d;}
    private GradientDrawable privacyCard(){return rounded(withAlpha(MINT,0x0B),16,withAlpha(MINT,0x33));}
    private GradientDrawable dot(int tone){GradientDrawable d=new GradientDrawable();d.setShape(GradientDrawable.OVAL);d.setColor(tone);return d;}
    @Override public void close(){closed=true;AndroidMessenger.clearCallHandler(callHandler);handler.removeCallbacks(hideFailedCall);CallSession active=call;call=null;releaseVideoUi();if(active!=null)active.close();handler.removeCallbacks(voiceWatchdog);MediaRecorder current=recorder;recorder=null;if(current!=null){try{current.stop();}catch(RuntimeException ignored){}try{current.release();}catch(RuntimeException ignored){}}voiceContact="";attachmentContact="";if(voiceFile!=null){voiceFile.delete();voiceFile=null;}stopPlayback();handler.removeCallbacksAndMessages(null);worker.shutdownNow();}
}
