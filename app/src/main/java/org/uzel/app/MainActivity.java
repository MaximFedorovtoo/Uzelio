package org.uzel.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.SystemClock;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.Locale;
import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

/** Native, accessible UI. Network state always comes from NodeController. */
public class MainActivity extends Activity {
    private static final int NAVY = 0xffedf7ff, GREEN = 0xff10edee, BG = 0xff031322, MUTED = 0xffa5bbcf, SURFACE=0xff092135;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() { public void run() { refresh(); handler.postDelayed(this, 1000); } };
    private NodeController controller;
    private LinearLayout content, nav, peers;
    private TextView status, address, shareValue, receivedValue, rateValue, clientsValue, limitValue, detail;
    private Button sharingButton, vpnButton, measureButton;
    private String pendingPeer, peerSignature = "";
    private String connectionPeer="",connectionName="";
    private boolean connectionRequested;
    private ProgressBar connectionProgress;
    private int selectedTab,selectedRegion;
    private TextView discoveryDetail,bootstrapAddress;
    private TextView sharingRateValue,sharingRateMinimum,sharingRateRequested;
    private Switch publicSwitch;
    private TextView publicDetail;
    private TextView pairingDetail;
    private Button pairingCreate,pairingImport,pairingShare;
    private NetworkMapView networkMap;
    private TextView searchTitle,searchHint,mapCount;
    private ProgressBar searchProgress;
    private Button searchAction,allRegions,searchDetails;
    private long searchStarted,retryCycle=-1;
    private String observedDiscovery="";
    private LinearLayout regionChips;
    private boolean advanced;
    private LinearLayout header,searchPanel;
    private HorizontalScrollView filterStrip;
    private PeerArtwork searchArtwork;
    private ScrollView pageScroll;
    private org.uzel.app.messenger.MessengerScreen messengerScreen;
    public static volatile boolean visible;
    public static volatile int activeTab;

    @Override protected void attachBaseContext(Context base) {
        String language = base.getSharedPreferences("ui", MODE_PRIVATE).getString("language", "");
        if (language != null && !language.isEmpty()) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(language));
            base = base.createConfigurationContext(config);
        }
        super.attachBaseContext(base);
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        controller = NodeController.get(this);
        messengerScreen = new org.uzel.app.messenger.MessengerScreen(this,state);
        if (state != null) { selectedTab = state.getInt("tab"); selectedRegion=state.getInt("region"); pendingPeer = state.getString("pendingPeer");connectionPeer=state.getString("connectionPeer","");connectionName=state.getString("connectionName","");connectionRequested=state.getBoolean("connectionRequested",false); }
        Intent requested=getIntent();
        int requestedTab=requested==null?-1:requested.getIntExtra("tab",-1);
        if(requestedTab>=0)selectedTab=requestedTab;
        getWindow().setStatusBarColor(BG); getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);
        LinearLayout root = column(); root.setBackgroundColor(BG); root.setFitsSystemWindows(true);
        header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(18), dp(8), dp(18), dp(8));
        root.addView(header);
        ScrollView scroll = new ScrollView(this);pageScroll=scroll; scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false); content = column(); content.setPadding(dp(12),dp(2),dp(12),dp(12)); scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        nav = new LinearLayout(this); nav.setGravity(Gravity.CENTER); nav.setBackground(raised(false,22)); nav.setPadding(dp(4),dp(7),dp(4),dp(5)); root.addView(nav);
        setContentView(root); showTab(selectedTab);
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt("tab",selectedTab);if(messengerScreen!=null)messengerScreen.saveState(out);out.putInt("region",selectedRegion); out.putString("pendingPeer",pendingPeer);out.putString("connectionPeer",connectionPeer);out.putString("connectionName",connectionName);out.putBoolean("connectionRequested",connectionRequested); }
    @Override protected void onResume() { super.onResume(); visible=true; activeTab=selectedTab; handler.removeCallbacks(ticker); ticker.run(); }
    @Override protected void onPause() { visible=false; handler.removeCallbacks(ticker); super.onPause(); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); int tab=intent==null?-1:intent.getIntExtra("tab",-1); if(tab>=0)showTab(tab); }
    @Override public void onBackPressed(){if(selectedTab==4&&messengerScreen.backToContacts())return;if(selectedTab!=0){showTab(0);return;}super.onBackPressed();}
    @Override protected void onDestroy(){if(messengerScreen!=null)messengerScreen.close();super.onDestroy();}
    private String s(String key,Object... args) { int id=stringResource(key); return args.length==0?getString(id):getString(id,args); }
    private static int stringResource(String key) {
        return switch (key) {
            case "app_name" -> R.string.app_name;
            case "home" -> R.string.home;
            case "nodes" -> R.string.nodes;
            case "statistics" -> R.string.statistics;
            case "settings" -> R.string.settings;
            case "home_intro" -> R.string.home_intro;
            case "connection" -> R.string.connection;
            case "choose_node" -> R.string.choose_node;
            case "node" -> R.string.node;
            case "your_node" -> R.string.your_node;
            case "sharing_explanation" -> R.string.sharing_explanation;
            case "start_sharing" -> R.string.start_sharing;
            case "stop_sharing" -> R.string.stop_sharing;
            case "fair_title" -> R.string.fair_title;
            case "fair_explanation" -> R.string.fair_explanation;
            case "clients" -> R.string.clients;
            case "share_limit" -> R.string.share_limit;
            case "address" -> R.string.address;
            case "unavailable" -> R.string.unavailable;
            case "unknown_country" -> R.string.unknown_country;
            case "disconnect" -> R.string.disconnect;
            case "nodes_intro" -> R.string.nodes_intro;
            case "add_invitation" -> R.string.add_invitation;
            case "refresh_nodes" -> R.string.refresh_nodes;
            case "copy_invitation" -> R.string.copy_invitation;
            case "share_invitation" -> R.string.share_invitation;
            case "stats_intro" -> R.string.stats_intro;
            case "shared_traffic" -> R.string.shared_traffic;
            case "received_traffic" -> R.string.received_traffic;
            case "upload_speed" -> R.string.upload_speed;
            case "measure_explanation" -> R.string.measure_explanation;
            case "measure" -> R.string.measure;
            case "language" -> R.string.language;
            case "choose_language" -> R.string.choose_language;
            case "how_it_works" -> R.string.how_it_works;
            case "network_notice" -> R.string.network_notice;
            case "privacy_notice" -> R.string.privacy_notice;
            case "country_notice" -> R.string.country_notice;
            case "per_second" -> R.string.per_second;
            case "no_nodes" -> R.string.no_nodes;
            case "peer_details" -> R.string.peer_details;
            case "latency" -> R.string.latency;
            case "reachable" -> R.string.reachable;
            case "unreachable" -> R.string.unreachable;
            case "connect" -> R.string.connect;
            case "invitation_hint" -> R.string.invitation_hint;
            case "invitation_notice" -> R.string.invitation_notice;
            case "cancel" -> R.string.cancel;
            case "add" -> R.string.add;
            case "invalid_invitation" -> R.string.invalid_invitation;
            case "invitation_unavailable" -> R.string.invitation_unavailable;
            case "ok" -> R.string.ok;
            case "invitation" -> R.string.invitation;
            case "copied" -> R.string.copied;
            case "system_language" -> R.string.system_language;
            case "status_off" -> R.string.status_off;
            case "status_starting" -> R.string.status_starting;
            case "status_sharing" -> R.string.status_sharing;
            case "status_connecting" -> R.string.status_connecting;
            case "status_connected" -> R.string.status_connected;
            case "status_measuring" -> R.string.status_measuring;
            case "status_error" -> R.string.status_error;
            case "status_no_peers" -> R.string.status_no_peers;
            case "status_unreachable" -> R.string.status_unreachable;
            case "status_capacity" -> R.string.status_capacity;
            case "status_need_sharing" -> R.string.status_need_sharing;
            case "status_need_measurement" -> R.string.status_need_measurement;
            case "endpoint_title" -> R.string.endpoint_title;
            case "endpoint_hint" -> R.string.endpoint_hint;
            case "endpoint_help" -> R.string.endpoint_help;
            case "save" -> R.string.save;
            case "invalid_host" -> R.string.invalid_host;
            case "saved" -> R.string.saved;
            case "notification_channel" -> R.string.notification_channel;
            case "notification_title" -> R.string.notification_title;
            case "notification_text" -> R.string.notification_text;
            case "notification_stop" -> R.string.notification_stop;
            case "vpn_apps" -> R.string.vpn_apps;
            case "vpn_apps_help" -> R.string.vpn_apps_help;
            case "apps_selected" -> R.string.apps_selected;
            case "choose_apps" -> R.string.choose_apps;
            case "apps_required" -> R.string.apps_required;
            case "apps_none" -> R.string.apps_none;
            case "apps_saved" -> R.string.apps_saved;
            case "public_network" -> R.string.public_network;
            case "public_network_help" -> R.string.public_network_help;
            case "add_first_participant" -> R.string.add_first_participant;
            case "share_public_contact" -> R.string.share_public_contact;
            case "public_contact_unavailable" -> R.string.public_contact_unavailable;
            case "public_network_off" -> R.string.public_network_off;
            case "public_network_pending" -> R.string.public_network_pending;
            case "network_bootstrap_required" -> R.string.network_bootstrap_required;
            case "network_relay_connected" -> R.string.network_relay_connected;
            case "network_direct_unverified" -> R.string.network_direct_unverified;
            case "status_select_apps" -> R.string.apps_required;
            default -> R.string.status_error;
        };
    }
    private int dp(float value) { return Math.round(value*getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private TextView label(String value,int size,int color,boolean bold) { TextView t=new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color);t.setTypeface(Typeface.create("sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));t.setIncludeFontPadding(false); t.setPadding(0,dp(4),0,dp(4)); return t; }
    private GradientDrawable background(int color,int radius) { GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private android.graphics.drawable.Drawable raised(boolean primary,int radius) {
        return new DepthSurface(getResources().getDisplayMetrics().density,radius,primary);
    }
    private LinearLayout card() { LinearLayout c=column(); c.setPadding(dp(12),dp(10),dp(12),dp(10)); c.setBackground(raised(false,20));c.setElevation(dp(5)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.bottomMargin=dp(10);p.topMargin=dp(2); content.addView(c,p); return c; }
    private Button button(LinearLayout parent,String text,boolean primary,Runnable action) { Button b=new Button(this); b.setText(text); b.setTextSize(14);b.setTypeface(Typeface.DEFAULT,Typeface.BOLD); b.setAllCaps(false); b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff8296a9,primary?BG:NAVY})); b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x407fffff),raised(primary,15),null)); b.setElevation(dp(primary?6:3));b.setPadding(dp(10),dp(8),dp(10),dp(8));b.setMinWidth(0);b.setMinimumWidth(0); b.setMinHeight(dp(50)); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2); p.topMargin=dp(8); parent.addView(b,p); b.setOnClickListener(v->action.run()); return b; }
    private View iconButton(String kind,String description,Runnable action) {
        FrameLayout box=new FrameLayout(this);box.setContentDescription(description);box.setBackground(new RippleDrawable(ColorStateList.valueOf(0x307fffff),raised(false,14),null));
        PeerArtwork icon=new PeerArtwork(this,kind,NAVY);FrameLayout.LayoutParams ip=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.CENTER);box.addView(icon,ip);box.setOnClickListener(v->action.run());box.setFocusable(true);box.setMinimumHeight(dp(48));box.setMinimumWidth(dp(48));return box;
    }
    private void buildHeader(int tab) {
        header.removeAllViews();
        if(tab==0||tab==4){
            TextView brand=label("Uzelio",32,NAVY,true);android.text.SpannableString word=new android.text.SpannableString("Uzelio");word.setSpan(new android.text.style.ForegroundColorSpan(GREEN),4,6,0);brand.setText(word);header.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
            header.addView(iconButton("settings",s("settings"),()->showTab(3)),new LinearLayout.LayoutParams(dp(48),dp(48)));
        }else{
            View back=iconButton("back",s("home"),()->showTab(0));back.setBackground(null);header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(48)));
            header.addView(label(tab==4?getString(R.string.chat_title):tab==1?getString(R.string.ui_map):s(tab==2?"statistics":"settings"),26,NAVY,true),new LinearLayout.LayoutParams(0,-2,1));
        }
    }
    private void showTab(int tab) {
        selectedTab=tab; activeTab=tab; content.removeAllViews(); nav.removeAllViews(); peerSignature="";
        status=address=shareValue=receivedValue=rateValue=clientsValue=limitValue=detail=null; publicSwitch=null; publicDetail=null; peers=null; sharingButton=vpnButton=measureButton=null;
        pairingDetail=null;pairingCreate=pairingImport=pairingShare=null;discoveryDetail=bootstrapAddress=null;
        connectionProgress=null;
        sharingRateValue=sharingRateMinimum=sharingRateRequested=null;
        networkMap=null;searchTitle=searchHint=mapCount=null;searchProgress=null;searchAction=allRegions=searchDetails=null;regionChips=null;searchPanel=null;filterStrip=null;searchArtwork=null;
        int[] targets={0,4,3};String[] symbols={"home","chat","profile"};
        for(int i=0;i<targets.length;i++) { final int index=targets[i];String title=i==0?s("home"):getString(i==1?R.string.chat_title:R.string.ui_profile);LinearLayout item=column();item.setGravity(Gravity.CENTER);int tint=index==tab||(index==0&&(tab==1||tab==2))?GREEN:MUTED;
            View artwork=i==1?new View(this){protected void onDraw(android.graphics.Canvas canvas){android.graphics.Paint p=new android.graphics.Paint(3);p.setColor(tint);p.setStyle(android.graphics.Paint.Style.STROKE);p.setStrokeWidth(dp(2));canvas.drawRoundRect(dp(3),dp(3),getWidth()-dp(3),getHeight()-dp(7),dp(5),dp(5),p);canvas.drawLine(dp(7),getHeight()-dp(7),dp(5),getHeight()-dp(2),p);}}:new PeerArtwork(this,symbols[i],tint);
            item.addView(artwork,new LinearLayout.LayoutParams(dp(27),dp(27)));TextView caption=label(title,10,tint,index==tab);caption.setGravity(Gravity.CENTER);item.addView(caption);item.setMinimumHeight(dp(58));item.setContentDescription(title);item.setSelected(index==tab);item.setFocusable(true);nav.addView(item,new LinearLayout.LayoutParams(0,-2,1));item.setOnClickListener(v->showTab(index)); }
        buildHeader(tab);
        if(tab==0) home(); else if(tab==1) nodes(); else if(tab==2) statistics(); else if(tab==4){if(messengerScreen.getParent()!=null)((ViewGroup)messengerScreen.getParent()).removeView(messengerScreen);content.addView(messengerScreen,new LinearLayout.LayoutParams(-1,Math.max(dp(420),pageScroll.getHeight()-dp(12))));pageScroll.post(()->{if(selectedTab==4&&messengerScreen.getParent()!=null){int height=pageScroll.getHeight()-dp(12);if(height>0)messengerScreen.setLayoutParams(new LinearLayout.LayoutParams(-1,Math.max(dp(420),height)));}});messengerScreen.refresh();}else settings();
        pageScroll.scrollTo(0,0);
        refresh();
    }
    private void home() {
        LinearLayout c=card();LinearLayout hero=new LinearLayout(this);hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.addView(new PeerArtwork(this,"network",GREEN),new LinearLayout.LayoutParams(dp(84),dp(84)));
        LinearLayout summary=column();LinearLayout.LayoutParams sp=new LinearLayout.LayoutParams(0,-2,1);sp.setMarginStart(dp(10));hero.addView(summary,sp);status=label("",21,NAVY,true);summary.addView(status);address=label("",14,MUTED,false);summary.addView(address);c.addView(hero);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        connectionProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);connectionProgress.setIndeterminate(true);connectionProgress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));connectionProgress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);summary.addView(connectionProgress,new LinearLayout.LayoutParams(-1,dp(6)));connectionProgress.setVisibility(View.GONE);
        LinearLayout actions=new LinearLayout(this);c.addView(actions);
        vpnButton=button(actions,s("choose_node"),false,()->{NodeController.Snapshot x=controller.snapshot();if(x.vpnConnected||isConnecting(x)){connectionRequested=false;controller.disconnect();refresh();}else if(connectionFailed(x)&&!connectionPeer.isEmpty())connect(connectionPeer);else showTab(1);});
        sharingButton=button(actions,getString(R.string.ui_share),true,()->{NodeController.Snapshot x=controller.snapshot();if(x.sharing)controller.stopSharing();else{requestNotifications();controller.startSharing();}refresh();});
        for(int i=0;i<2;i++){LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(0,-2,1);bp.topMargin=dp(4);if(i==0)bp.setMarginEnd(dp(8));actions.getChildAt(i).setLayoutParams(bp);}
        LinearLayout stats=card();stats.setOrientation(LinearLayout.HORIZONTAL);
        receivedValue=statCell(stats,getString(R.string.ui_received));shareValue=statCell(stats,getString(R.string.ui_sent));clientsValue=statCell(stats,getString(R.string.ui_connected_label));
        LinearLayout map=card();LinearLayout mh=new LinearLayout(this);mh.setGravity(Gravity.CENTER_VERTICAL);mh.addView(label(getString(R.string.ui_world_nodes),16,NAVY,true),new LinearLayout.LayoutParams(0,-2,1));mh.addView(new PeerArtwork(this,"chevron",MUTED),new LinearLayout.LayoutParams(dp(18),dp(18)));map.addView(mh);
        networkMap=new NetworkMapView(this);map.addView(networkMap,new LinearLayout.LayoutParams(-1,dp(175)));networkMap.setOnRegionSelectedListener(region->{selectedRegion=region;showTab(1);});
        button(map,getString(R.string.ui_open_map),false,()->showTab(1));
        LinearLayout trafficCard=card();button(trafficCard,getString(R.string.ui_traffic),false,()->showTab(2));
        LinearLayout apps=card();LinearLayout ah=new LinearLayout(this);ah.setGravity(Gravity.CENTER_VERTICAL);ah.addView(label(getString(R.string.ui_apps),16,NAVY,true),new LinearLayout.LayoutParams(0,-2,1));ah.addView(new PeerArtwork(this,"chevron",MUTED),new LinearLayout.LayoutParams(dp(20),dp(20)));apps.addView(ah);apps.setOnClickListener(v->chooseApps());apps.setFocusable(true);apps.setContentDescription(getString(R.string.ui_choose_apps));
        boolean allApps=AppSelection.allApplications(this);
        LinearLayout icons=new LinearLayout(this);if(!allApps)for(String pkg:AppSelection.validPackages(this)){if(icons.getChildCount()>=5)break;try{FrameLayout tile=new FrameLayout(this);tile.setBackground(raised(false,14));ImageView icon=new ImageView(this);icon.setImageDrawable(getPackageManager().getApplicationIcon(pkg));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);tile.addView(icon,new FrameLayout.LayoutParams(dp(35),dp(35),Gravity.CENTER));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(60),dp(54));ip.setMarginEnd(dp(10));icons.addView(tile,ip);}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}}
        if(icons.getChildCount()==0){icons.addView(new PeerArtwork(this,"apps",GREEN),new LinearLayout.LayoutParams(dp(38),dp(48)));TextView choose=label(getString(allApps?R.string.vpn_scope_all:R.string.ui_choose_apps),14,MUTED,false);choose.setGravity(Gravity.CENTER_VERTICAL);icons.addView(choose,new LinearLayout.LayoutParams(-2,dp(48)));}
        apps.addView(icons);
        detail=label("",14,0xffff8e9a,false); content.addView(detail);
    }
    private TextView statCell(LinearLayout row,String title) {
        if(row.getChildCount()>0){View line=new View(this);line.setBackgroundColor(0xff28435a);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(1),dp(42));lp.gravity=Gravity.CENTER_VERTICAL;row.addView(line,lp);}
        LinearLayout cell=column();cell.setPadding(dp(6),0,dp(3),0);cell.addView(label(title,11,MUTED,false));TextView value=label("",18,NAVY,true);cell.addView(value);row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));return value;
    }
    private void nodes() {
        if(searchStarted==0)searchStarted=SystemClock.elapsedRealtime();
        mapCount=label("",14,MUTED,false);mapCount.setPadding(dp(16),0,0,0);content.addView(mapCount);
        networkMap=new NetworkMapView(this);content.addView(networkMap,new LinearLayout.LayoutParams(-1,dp(265)));
        networkMap.setOnRegionSelectedListener(region->{selectedRegion=region;showTab(1);});
        HorizontalScrollView filter=new HorizontalScrollView(this);filterStrip=filter;filter.setHorizontalScrollBarEnabled(false);regionChips=new LinearLayout(this);filter.addView(regionChips);content.addView(filter);
        int[] names={R.string.discovery_all,R.string.discovery_africa,R.string.discovery_americas,R.string.discovery_asia,R.string.discovery_europe,R.string.discovery_oceania,R.string.discovery_unknown};
        selectedRegion=Math.max(0,Math.min(selectedRegion,names.length-1));
        int[] order={0,4,3,2,1,5,6};for(int i:order){final int region=i;Button chip=button(regionChips,i==0?getString(R.string.ui_filter_all):getString(names[i]),i==selectedRegion,()->{selectedRegion=region;showTab(1);});LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(dp(106),-2);cp.setMarginEnd(dp(8));cp.bottomMargin=dp(12);chip.setLayoutParams(cp);chip.setSelected(i==selectedRegion);if(i==selectedRegion)filter.post(()->filter.smoothScrollTo(Math.max(0,chip.getLeft()-dp(8)),0));}
        LinearLayout search=column();searchPanel=search;search.setGravity(Gravity.CENTER_HORIZONTAL);search.setPadding(dp(8),0,dp(8),dp(16));content.addView(search);
        FrameLayout symbol=new FrameLayout(this);search.addView(symbol,new LinearLayout.LayoutParams(dp(110),dp(110)));
        searchArtwork=new PeerArtwork(this,"search",MUTED);symbol.addView(searchArtwork,new FrameLayout.LayoutParams(-1,-1));
        searchProgress=new ProgressBar(this);searchProgress.setIndeterminateTintList(ColorStateList.valueOf(GREEN));symbol.addView(searchProgress,new FrameLayout.LayoutParams(dp(84),dp(84),Gravity.CENTER));
        searchTitle=label("",24,NAVY,true);searchTitle.setGravity(Gravity.CENTER);search.addView(searchTitle,new LinearLayout.LayoutParams(-1,-2));
        searchHint=label("",15,MUTED,false);searchHint.setGravity(Gravity.CENTER);search.addView(searchHint,new LinearLayout.LayoutParams(-1,-2));
        searchAction=button(search,getString(R.string.ui_retry),true,this::searchAction);
        allRegions=button(search,getString(R.string.ui_all_regions),false,()->{selectedRegion=PeerRegions.ALL;showTab(1);});
        searchDetails=button(search,getString(R.string.ui_more),false,()->{advanced=true;showTab(3);});searchDetails.setBackground(null);searchDetails.setElevation(0);searchDetails.setTextColor(GREEN);
        detail=label("",14,0xffff8e9a,false);content.addView(detail);
        peers=column();content.addView(peers);
    }
    private void manualPairing() {
        content.addView(label(getString(R.string.discovery_manual),19,NAVY,true));
        LinearLayout pairingCard=card();
        pairingCard.addView(label(getString(R.string.nat_title),19,NAVY,true));
        pairingCard.addView(label(getString(R.string.nat_help),15,MUTED,false));
        pairingDetail=label("",15,NAVY,false);pairingCard.addView(pairingDetail);
        pairingCreate=button(pairingCard,getString(R.string.nat_create),true,()->{controller.createPairing();refresh();});
        pairingImport=button(pairingCard,getString(R.string.nat_import),false,this::importPairing);
        pairingShare=button(pairingCard,getString(R.string.nat_share),false,this::sharePairing);
        button(pairingCard,s("cancel"),false,()->{controller.cancelPairing();refresh();});
        LinearLayout c=card();button(c,s("add_invitation"),false,this::addInvitation);
        button(c,s("copy_invitation"),false,()->ownInvitation(false));button(c,s("share_invitation"),false,()->ownInvitation(true));
    }
    private void statistics() {
        LinearLayout c=card(); c.addView(label(s("shared_traffic"),14,MUTED,false));shareValue=label("",30,NAVY,true);c.addView(shareValue);
        c=card();c.addView(label(s("received_traffic"),14,MUTED,false));receivedValue=label("",30,NAVY,true);c.addView(receivedValue);
        c=card();c.addView(label(s("upload_speed"),14,MUTED,false));rateValue=label("",26,NAVY,true);c.addView(rateValue);
        measureButton=button(c,s("measure"),true,()->{controller.measure();refresh();});
        detail=label("",14,0xffff8e9a,false);content.addView(detail);
    }
    private void settings() {
        LinearLayout apps = card();
        apps.addView(label(getString(R.string.ui_apps),18,NAVY,true));
        apps.addView(label(AppSelection.allApplications(this)?getString(R.string.vpn_scope_all):s("apps_selected",AppSelection.validPackages(this).size()),15,NAVY,true));
        button(apps,getString(R.string.ui_choose_apps),false,this::chooseApps);
        LinearLayout sharingRate=card();
        sharingRate.addView(label(getString(R.string.sharing_rate_title),18,NAVY,true));
        sharingRateValue=label("",24,NAVY,true);sharingRate.addView(sharingRateValue);
        sharingRateMinimum=label("",14,MUTED,false);sharingRate.addView(sharingRateMinimum);
        sharingRateRequested=label("",14,MUTED,false);sharingRate.addView(sharingRateRequested);
        button(sharingRate,getString(R.string.sharing_rate_edit),false,this::editSharingRate);
        LinearLayout contacts=card();contacts.addView(label(getString(R.string.directory_contacts_title),18,NAVY,true));button(contacts,getString(R.string.directory_code_add),false,this::addDirectoryCode);
        LinearLayout language=card();button(language,s("choose_language"),false,this::chooseLanguage);
        button(language,getString(R.string.ui_more),false,()->{advanced=!advanced;showTab(3);});
        if(!advanced)return;
        LinearLayout bootstrap=card();bootstrap.addView(label(getString(R.string.bootstrap_title),19,NAVY,true));
        bootstrap.addView(label(getString(R.string.bootstrap_help),15,MUTED,false));
        bootstrapAddress=label("",14,MUTED,false);bootstrapAddress.setTextIsSelectable(true);bootstrap.addView(bootstrapAddress);
        discoveryDetail=label("",15,NAVY,false);bootstrap.addView(discoveryDetail);
        button(bootstrap,getString(R.string.bootstrap_edit),false,this::editBootstrap);
        LinearLayout localContacts=card();Switch local=new Switch(this);local.setText(R.string.directory_private);local.setTextColor(NAVY);local.setMinHeight(dp(52));local.setChecked(controller.privateDirectoriesAllowed());localContacts.addView(local);localContacts.addView(label(getString(R.string.directory_private_hint),14,MUTED,false));local.setOnClickListener(v->controller.setPrivateDirectoriesAllowed(local.isChecked()));
        LinearLayout networkCard = card();
        publicSwitch = new Switch(this);
        publicSwitch.setText(s("public_network"));
        publicSwitch.setTextSize(18);
        publicSwitch.setTextColor(NAVY);
        publicSwitch.setMinHeight(dp(52));
        publicSwitch.setChecked(controller.snapshot().publicNetwork);
        networkCard.addView(publicSwitch,new LinearLayout.LayoutParams(-1,-2));
        publicSwitch.setOnClickListener(view -> {controller.setPublicNetwork(publicSwitch.isChecked());refresh();});
        networkCard.addView(label(s("public_network_help"),15,MUTED,false));
        publicDetail=label("",15,NAVY,false);networkCard.addView(publicDetail);
        button(networkCard,s("add_first_participant"),false,this::addInvitation);
        button(networkCard,s("share_public_contact"),false,this::sharePublicContact);
        LinearLayout c=card();c.addView(label(s("language"),18,NAVY,true));button(c,s("choose_language"),false,this::chooseLanguage);
        c=card();c.addView(label(s("endpoint_title"),18,NAVY,true));c.addView(label(s("endpoint_help"),15,MUTED,false));button(c,s("endpoint_title"),false,this::editAdvertisedHost);
        c=card();c.addView(label(s("how_it_works"),19,NAVY,true));c.addView(label(s("network_notice"),15,MUTED,false));c.addView(label(s("privacy_notice"),15,MUTED,false));c.addView(label(s("country_notice"),15,MUTED,false));c.addView(label(s("measure_explanation"),15,MUTED,false));
        manualPairing();
    }
    private void refresh() {
        if(selectedTab==4&&messengerScreen!=null)messengerScreen.refresh();
        if(controller==null)return; NodeController.Snapshot x=controller.snapshot();
        renderSnapshot(x);
    }
    private void renderSnapshot(NodeController.Snapshot x) {
        if(!x.connecting&&!x.busy)connectionRequested=false;
        boolean connecting=isConnecting(x);
        if(status!=null)status.setText(connecting?getString(R.string.ui_connecting):x.vpnConnected?getString(R.string.ui_connected):connectionFailed(x)?getString(R.string.ui_connection_failed):"OFF".equals(x.status)||"SHARING".equals(x.status)?getString(R.string.ui_disconnected):localStatus(x.status));
        if(connectionProgress!=null)connectionProgress.setVisibility(connecting?View.VISIBLE:View.GONE);
        updateDiscovery(x);
        if(discoveryDetail!=null){String value=getString(R.string.discovery_state,getString(discoveryStateResource(x.discoveryState)));if(x.bootstrapHosting)value+="\n"+getString(R.string.discovery_hosting,x.bootstrapPort);discoveryDetail.setText(value);}
        if(bootstrapAddress!=null)bootstrapAddress.setText(empty(x.discoveryURL)?getString(R.string.discovery_not_configured):x.discoveryURL);
        if(publicSwitch!=null)publicSwitch.setChecked(x.publicNetwork);
        if(publicDetail!=null)publicDetail.setText(networkDetail(x.publicNetwork,x.networkDetail));
        if(pairingDetail!=null)pairingDetail.setText(getString(!x.sharing?R.string.nat_start_sharing:switch(x.pairingState){
            case "GATHERING","CONNECTING" -> R.string.nat_connecting;
            case "OFFER_READY" -> R.string.nat_offer_ready;
            case "ANSWER_READY" -> R.string.nat_answer_ready;
            case "CONNECTED" -> R.string.nat_connected;
            case "INVALID" -> R.string.nat_invalid;
            case "EXPIRED" -> R.string.nat_expired;
            case "UNAVAILABLE","CAPACITY" -> R.string.nat_unavailable;
            default -> R.string.nat_idle;
        }));
        if(pairingCreate!=null)pairingCreate.setEnabled(x.sharing&&!x.busy);
        if(pairingImport!=null)pairingImport.setEnabled(x.sharing&&!x.busy);
        if(pairingShare!=null)pairingShare.setEnabled(!x.pairingCode.isEmpty());
        if(address!=null){address.setText(connecting&&!connectionName.isEmpty()?getString(R.string.ui_connecting_to,connectionName):x.vpnConnected&&!empty(x.connectedCountry)?flag(x.connectedCountry)+"  "+country(x.connectedCountry):x.sharing?getString(R.string.status_sharing):"");address.setVisibility(address.length()==0?View.GONE:View.VISIBLE);}
        if(clientsValue!=null)clientsValue.setText(getString(R.string.ui_connected_count,x.clients));
        if(limitValue!=null){limitValue.setText(x.shareLimitBps>0?getString(R.string.ui_limit,speed(x.shareLimitBps)):"");limitValue.setVisibility(x.shareLimitBps>0?View.VISIBLE:View.GONE);}
        if(sharingRateValue!=null)sharingRateValue.setText(x.shareLimitBps==0?getString(R.string.sharing_rate_unlimited):getString(R.string.sharing_rate_effective,SharingRateInput.format(x.shareLimitBps)));
        if(sharingRateMinimum!=null)sharingRateMinimum.setText(sharingRateMinimumText(x.minimumShareRateBps));
        if(sharingRateRequested!=null){
            boolean raised=x.requestedShareRateBps>0 && x.requestedShareRateBps!=x.shareLimitBps;
            sharingRateRequested.setText(raised?getString(R.string.sharing_rate_requested,SharingRateInput.format(x.requestedShareRateBps)):"");
            sharingRateRequested.setVisibility(raised?View.VISIBLE:View.GONE);
        }
        if(sharingButton!=null){sharingButton.setText(getString(x.sharing?R.string.ui_sharing_on:R.string.ui_share));sharingButton.setContentDescription(getString(x.sharing?R.string.ui_stop_share:R.string.ui_share));sharingButton.setEnabled(x.sharing||!x.busy);}
        if(vpnButton!=null){vpnButton.setText(connecting?s("cancel"):x.vpnConnected?s("disconnect"):connectionFailed(x)&&!connectionPeer.isEmpty()?getString(R.string.ui_connection_retry):s("choose_node"));vpnButton.setEnabled(x.vpnConnected||connecting||!x.busy);}
        if(shareValue!=null)shareValue.setText(Formatter.formatFileSize(this,x.sharedBytes));
        if(receivedValue!=null)receivedValue.setText(Formatter.formatFileSize(this,x.receivedBytes));
        if(rateValue!=null)rateValue.setText(speed(x.uploadBps));
        if(measureButton!=null)measureButton.setEnabled(!x.busy);
        if(detail!=null){boolean error="ERROR".equals(x.status)||"UNREACHABLE".equals(x.status)||"CAPACITY".equals(x.status)||"NEED_SHARING".equals(x.status)||"NEED_MEASUREMENT".equals(x.status)||"SELECT_APPS".equals(x.status); detail.setText(error?localStatus(x.status):"");detail.setVisibility(error?View.VISIBLE:View.GONE);}
        if(peers!=null){StringBuilder signature=new StringBuilder().append(selectedRegion).append(x.busy).append(x.sharing).append(x.vpnConnected); if(x.peers!=null)for(NodeController.Peer p:x.peers)signature.append(p.id).append(p.name).append(p.country).append(p.reachable).append(p.clients).append(p.latencyMs).append(p.upload).append(p.autoDiscovered).append(p.announced).append(p.freeSlots); if(!Objects.equals(peerSignature,signature.toString())){peerSignature=signature.toString();renderPeers(x);}}
    }
    private static boolean visiblePeer(NodeController.Peer p){return p.reachable||(p.autoDiscovered&&p.announced);}
    private void updateDiscovery(NodeController.Snapshot x) {
        if(networkMap==null&&searchTitle==null)return;
        long now=SystemClock.elapsedRealtime();
        String phase=x.discoveryState+":"+x.status+":"+x.sharing;
        if(!phase.equals(observedDiscovery)){observedDiscovery=phase;if(retryCycle<0)searchStarted=now;}
        if(retryCycle>=0&&x.completedDiscoveryRefresh>=retryCycle)retryCycle=-1;
        int[] counts=new int[7];for(NodeController.Peer p:x.peers)if(visiblePeer(p)){counts[0]++;counts[PeerRegions.group(p.country)]++;}
        if(networkMap!=null)networkMap.setData(counts,selectedTab==1?selectedRegion:0);
        if(mapCount!=null){mapCount.setText(getString(R.string.ui_found,counts[0]));mapCount.setVisibility(counts[0]>0?View.VISIBLE:View.GONE);}
        if(searchTitle==null)return;
        int count=counts[selectedRegion];
        boolean preparing=x.busy&&("STARTING".equals(x.status)||"MEASURING".equals(x.status));
        SearchPresentation.State state=SearchPresentation.resolve(x.sharing,preparing,x.discoveryState,count,now-searchStarted,retryCycle>=0);
        searchPanel.setVisibility(state==SearchPresentation.State.FOUND?View.GONE:View.VISIBLE);
        filterStrip.setVisibility(counts[0]>0?View.VISIBLE:View.GONE);
        int title=switch(state){case SHARE_REQUIRED->R.string.ui_share_to_search;case PREPARING->R.string.ui_preparing;case SEARCHING->R.string.ui_searching;case SLOW->R.string.ui_search_slow;case FOUND->R.string.ui_found;case EMPTY->selectedRegion==0?R.string.ui_empty:R.string.ui_region_empty;case OFFLINE->R.string.ui_catalog_offline;case SETUP->R.string.ui_setup;};
        searchTitle.setText(state==SearchPresentation.State.FOUND?getString(title,count):getString(title));
        searchProgress.setVisibility(state==SearchPresentation.State.SEARCHING||state==SearchPresentation.State.PREPARING?View.VISIBLE:View.GONE);
        boolean loading=state==SearchPresentation.State.SEARCHING||state==SearchPresentation.State.PREPARING;
        searchArtwork.setVisibility(loading?View.INVISIBLE:View.VISIBLE);searchArtwork.setKind(state==SearchPresentation.State.OFFLINE?"offline":state==SearchPresentation.State.SHARE_REQUIRED?"share":"search");searchArtwork.setTint(state==SearchPresentation.State.OFFLINE?0xffffc266:GREEN);
        boolean hint=state==SearchPresentation.State.EMPTY||state==SearchPresentation.State.OFFLINE||state==SearchPresentation.State.SLOW;
        searchHint.setText(getString(state==SearchPresentation.State.EMPTY?R.string.ui_empty_hint:R.string.ui_retry_hint));searchHint.setVisibility(hint?View.VISIBLE:View.GONE);
        searchAction.setText(getString(state==SearchPresentation.State.SHARE_REQUIRED?R.string.ui_share:state==SearchPresentation.State.SETUP?R.string.ui_advanced:R.string.ui_retry));
        searchAction.setEnabled(!x.busy&&state!=SearchPresentation.State.SEARCHING&&state!=SearchPresentation.State.PREPARING);
        allRegions.setVisibility(selectedRegion!=0?View.VISIBLE:View.GONE);
        searchDetails.setVisibility(state==SearchPresentation.State.OFFLINE||state==SearchPresentation.State.SETUP||state==SearchPresentation.State.SLOW?View.VISIBLE:View.GONE);
    }
    private void searchAction() {
        NodeController.Snapshot x=controller.snapshot();
        searchStarted=SystemClock.elapsedRealtime();
        if(!x.sharing){requestNotifications();controller.startSharing();}
        else if("NOT_CONFIGURED".equals(x.discoveryState)){advanced=true;showTab(3);return;}
        else {retryCycle=controller.refreshDiscovery();controller.refreshPeers();}
        refresh();
    }
    private String localStatus(String code) { return s("status_"+(code==null?"off":code.toLowerCase(Locale.ROOT))); }
    private String country(String code){if(empty(code))return s("unknown_country");return new Locale("",code).getDisplayCountry(getResources().getConfiguration().getLocales().get(0));}
    private String flag(String code){if(code==null||!code.matches("[A-Za-z]{2}"))return "";String normalized=code.toUpperCase(Locale.ROOT);return new String(Character.toChars(0x1f1e6+normalized.charAt(0)-'A'))+new String(Character.toChars(0x1f1e6+normalized.charAt(1)-'A'));}
    private String speed(long bytes){return getString(R.string.speed_mbps,Math.max(0,bytes)*8.0/1_000_000.0);}
    private boolean empty(String text){return text==null||text.isEmpty();}
    private static int discoveryStateResource(String state){
        if(state==null)return R.string.discovery_not_configured;
        return switch(state){case "STARTING"->R.string.discovery_starting;case "ONLINE"->R.string.discovery_online;case "OFFLINE"->R.string.discovery_offline;case "PAIRING"->R.string.discovery_pairing;default->R.string.discovery_not_configured;};
    }
    private void addDirectoryCode(){
        EditText input=new EditText(this);input.setMinLines(3);input.setMaxLines(6);input.setHint(R.string.directory_code_hint);input.setTextColor(NAVY);input.setHintTextColor(MUTED);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(44000)});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.directory_code_add).setView(input).setNegativeButton(s("cancel"),null).setPositiveButton(s("add"),null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            try{controller.addDirectoryContact(input.getText().toString());input.setText("");dialog.dismiss();refresh();}
            catch(java.io.IOException|IllegalArgumentException invalid){input.setError(getString(R.string.directory_code_invalid));}
        }));dialog.show();
    }
    private void editBootstrap(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint(R.string.bootstrap_hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2048)});input.setText(controller.snapshot().discoveryURL);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.bootstrap_title).setMessage(R.string.bootstrap_help).setView(input).setNegativeButton(s("cancel"),null).setPositiveButton(s("save"),null).create();
        dialog.setOnShowListener(view->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            try{controller.setBootstrapUrl(input.getText().toString().trim());dialog.dismiss();Toast.makeText(this,s("saved"),Toast.LENGTH_SHORT).show();refresh();}
            catch(IllegalArgumentException invalid){input.setError(getString(R.string.bootstrap_invalid));}
        }));dialog.show();
    }
    private void renderPeers(NodeController.Snapshot x){
        peers.removeAllViews();int count=0;
        if(x.peers!=null)for(NodeController.Peer p:x.peers){
            if(!visiblePeer(p))continue;
            if(selectedRegion!=PeerRegions.ALL&&PeerRegions.group(p.country)!=selectedRegion)continue;
            count++;
            LinearLayout c=column();c.setBackground(raised(false,20));c.setElevation(dp(3));c.setPadding(dp(12),dp(10),dp(12),dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.bottomMargin=dp(10);peers.addView(c,lp);
            LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);c.addView(row);
            TextView flag=label(flag(p.country),30,NAVY,false);row.addView(flag,new LinearLayout.LayoutParams(dp(46),dp(48)));
            LinearLayout info=column();LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(0,-2,1);ip.setMarginEnd(dp(8));row.addView(info,ip);info.addView(label(country(p.country),16,NAVY,true));
            boolean announced=p.autoDiscovered&&p.announced;
            int slots=p.reachable?Math.max(0,3-p.clients):Math.max(0,Math.min(3,p.freeSlots));
            info.addView(label(getString(R.string.ui_slots,slots),12,MUTED,false));
            String state=getString(p.reachable?R.string.ui_verified:R.string.ui_announced);
            boolean canConnect=SearchPresentation.canConnect(p.reachable,p.paired,announced,slots);
            boolean large=getResources().getConfiguration().fontScale>1.6f;
            Button connect=button(large?c:row,getString(canConnect?R.string.ui_connect:R.string.ui_full),true,()->connect(p.id));if(!large){connect.setLayoutParams(new LinearLayout.LayoutParams(dp(124),dp(54)));connect.setMaxLines(1);connect.setAutoSizeTextTypeUniformWithConfiguration(10,14,1,android.util.TypedValue.COMPLEX_UNIT_SP);}connect.setEnabled(canConnect&&x.sharing&&!x.busy);
            TextView meta=label(state,11,p.reachable?GREEN:MUTED,false);c.addView(meta);c.setContentDescription(country(p.country)+", "+state+", "+getString(R.string.ui_slots,slots));
        }
    }
    private static boolean connectionFailed(NodeController.Snapshot x){return !x.connecting&&!x.vpnConnected&&("ERROR".equals(x.status)||"UNREACHABLE".equals(x.status)||"CAPACITY".equals(x.status));}
    private boolean isConnecting(NodeController.Snapshot x){return x.connecting||(connectionRequested&&x.busy&&!x.vpnConnected);}
    private void beginConnection(String id){connectionPeer=id;connectionName=id.substring(0,Math.min(12,id.length()));for(NodeController.Peer peer:controller.snapshot().peers)if(id.equals(peer.id)&&!empty(peer.country)){connectionName=country(peer.country)+" · "+connectionName;break;}connectionRequested=true;controller.connect(id);showTab(0);}
    private void connect(String id){if(!hasSelectedApps())return;Intent prepare=VpnService.prepare(this);pendingPeer=id;if(prepare!=null)startActivityForResult(prepare,71);else{pendingPeer=null;beginConnection(id);}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==71){String id=pendingPeer;pendingPeer=null;if(result==RESULT_OK&&id!=null&&hasSelectedApps())beginConnection(id);}if(messengerScreen!=null)messengerScreen.onActivityResult(request,result,data);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(request,permissions,grantResults);if(messengerScreen!=null)messengerScreen.onRequestPermissionsResult(request,permissions,grantResults);}
    private void requestNotifications(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},72);}
    private String sharingRateMinimumText(long minimum){
        return minimum>0?getString(R.string.sharing_rate_minimum,SharingRateInput.format(minimum)):getString(R.string.sharing_rate_minimum_unknown);
    }
    private void editSharingRate(){
        NodeController.Snapshot state=controller.snapshot();
        LinearLayout panel=column();panel.setPadding(dp(20),dp(8),dp(20),dp(8));
        panel.addView(label(getString(R.string.sharing_rate_help),14,MUTED,false));
        TextView minimum=label(sharingRateMinimumText(state.minimumShareRateBps),14,MUTED,false);panel.addView(minimum);
        RadioGroup choices=new RadioGroup(this);panel.addView(choices);
        RadioButton unlimited=new RadioButton(this);unlimited.setId(View.generateViewId());unlimited.setText(R.string.sharing_rate_unlimited);unlimited.setTextColor(NAVY);unlimited.setMinHeight(dp(48));choices.addView(unlimited);
        RadioButton manual=new RadioButton(this);manual.setId(View.generateViewId());manual.setText(R.string.sharing_rate_manual);manual.setTextColor(NAVY);manual.setMinHeight(dp(48));choices.addView(manual);
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint(R.string.sharing_rate_unit);input.setContentDescription(getString(R.string.sharing_rate_unit));
        // Keep text validation intact for pasted input while showing a decimal keyboard.
        input.setRawInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setTextColor(NAVY);input.setHintTextColor(MUTED);panel.addView(input);
        if(state.requestedShareRateBps>0)input.setText(SharingRateInput.format(state.requestedShareRateBps));
        choices.check(state.requestedShareRateBps==0?unlimited.getId():manual.getId());
        input.setEnabled(state.requestedShareRateBps>0);
        choices.setOnCheckedChangeListener((group,id)->{input.setEnabled(id==manual.getId());input.setError(null);});
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(R.string.sharing_rate_title).setView(panel).setNegativeButton(s("cancel"),null).setPositiveButton(s("save"),null).create();
        dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{
            NodeController.Snapshot latest=controller.snapshot();minimum.setText(sharingRateMinimumText(latest.minimumShareRateBps));
            try {
                long rate=unlimited.isChecked()?0:SharingRateInput.parse(input.getText().toString(),latest.minimumShareRateBps);
                controller.setShareRateLimit(rate);dialog.dismiss();refresh();
            } catch(IllegalArgumentException e){input.setError(getString(R.string.sharing_rate_invalid,SharingRateInput.format(latest.minimumShareRateBps)));}
        }));
        dialog.show();
    }
    private void addInvitation(){EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setHint(s("invitation_hint"));input.setMinLines(3);input.setPadding(dp(20),dp(12),dp(20),dp(12));AlertDialog dialog=new AlertDialog.Builder(this).setTitle(s("add_invitation")).setMessage(s("invitation_notice")).setView(input).setNegativeButton(s("cancel"),null).setPositiveButton(s("add"),null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{controller.addInvitation(input.getText().toString().trim());dialog.dismiss();refresh();}catch(IllegalArgumentException e){input.setError(s("invalid_invitation"));}}));dialog.show();}
    private void ownInvitation(boolean share){String value=controller.snapshot().invitation;if(empty(value)){new AlertDialog.Builder(this).setMessage(s("invitation_unavailable")).setPositiveButton(s("ok"),null).show();return;}if(share){Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("text/plain");intent.putExtra(Intent.EXTRA_TEXT,value);startActivity(Intent.createChooser(intent,s("share_invitation")));}else{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText(s("invitation"),value));Toast.makeText(this,s("copied"),Toast.LENGTH_SHORT).show();}}
    private void importPairing(){
        EditText input=new EditText(this);input.setMinLines(3);
        input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(100_000)});
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        new AlertDialog.Builder(this).setTitle(R.string.nat_import).setMessage(R.string.nat_secret_notice).setView(input)
                .setNegativeButton(s("cancel"),null).setPositiveButton(s("add"),(dialog,which)->{
                    controller.acceptPairing(input.getText().toString().trim());input.setText("");refresh();
                }).show();
    }
    private void sharePairing(){
        String value=controller.snapshot().pairingCode;if(value.isEmpty())return;
        new AlertDialog.Builder(this).setTitle(R.string.nat_share).setMessage(R.string.nat_secret_notice)
                .setNegativeButton(s("cancel"),null).setNeutralButton(R.string.nat_copy,(dialog,which)->{
                    ClipData clip=ClipData.newPlainText(getString(R.string.nat_title),value);
                    android.os.PersistableBundle extras=new android.os.PersistableBundle();extras.putBoolean("android.content.extra.IS_SENSITIVE",true);
                    clip.getDescription().setExtras(extras);
                    ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(clip);
                    Toast.makeText(this,s("copied"),Toast.LENGTH_SHORT).show();
                }).setPositiveButton(R.string.nat_share,(dialog,which)->{
                    Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,value);
                    startActivity(Intent.createChooser(send,getString(R.string.nat_share)));
                }).show();
    }
    private String networkDetail(boolean enabled,String code) {
        if(!enabled)return s("public_network_off");
        if(code==null)return s("public_network_pending");
        return s(switch(code){
            case "BOOTSTRAP_REQUIRED" -> "network_bootstrap_required";
            case "DISCOVERING" -> "public_network_pending";
            case "RELAY_CONNECTED" -> "network_relay_connected";
            case "DIRECT_UNVERIFIED" -> "network_direct_unverified";
            case "PUBLIC_OFF" -> "public_network_off";
            default -> "public_network_pending";
        });
    }
    private void sharePublicContact() {
        NodeController.Snapshot state=controller.snapshot();
        if(!state.publicNetwork||empty(state.publicContact)) {
            new AlertDialog.Builder(this).setMessage(s("public_contact_unavailable"))
                    .setPositiveButton(s("ok"),null).show();
            return;
        }
        Intent share=new Intent(Intent.ACTION_SEND).setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT,state.publicContact);
        startActivity(Intent.createChooser(share,s("share_public_contact")));
    }
    private boolean hasSelectedApps() {
        if (AppSelection.vpnReady(this)) return true;
        new AlertDialog.Builder(this).setTitle(s("vpn_apps")).setMessage(s("apps_required"))
                .setNegativeButton(s("cancel"), null)
                .setPositiveButton(s("choose_apps"), (dialog, which) -> { showTab(3); chooseApps(); }).show();
        return false;
    }

    private void chooseApps() {
        List<AppSelection.App> candidates = AppSelection.candidateApps(this);
        Set<String> selected = new HashSet<>(AppSelection.load(this));
        List<AppSelection.App> shown = new java.util.ArrayList<>(candidates);
        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        list.setDividerHeight(0);
        android.util.LruCache<String,android.graphics.drawable.Drawable> icons = new android.util.LruCache<>(64);
        BaseAdapter appAdapter=new BaseAdapter() {
            public int getCount(){return shown.size();}
            public Object getItem(int position){return shown.get(position);}
            public long getItemId(int position){return position;}
            public View getView(int position,View recycled,ViewGroup parent){
                AppChoiceRow row=recycled instanceof AppChoiceRow?(AppChoiceRow)recycled:new AppChoiceRow();
                AppSelection.App app=shown.get(position);
                android.graphics.drawable.Drawable icon=icons.get(app.packageName);
                if(icon==null){
                    try{icon=getPackageManager().getApplicationIcon(app.packageName);}
                    catch(android.content.pm.PackageManager.NameNotFoundException removed){icon=getPackageManager().getDefaultActivityIcon();}
                    icons.put(app.packageName,icon);
                }
                row.icon.setImageDrawable(icon);row.name.setText(app.label);row.packageName.setText(app.packageName);
                row.setContentDescription(app.label+", "+app.packageName);
                row.setChecked(selected.contains(app.packageName));return row;
            }
        };
        list.setAdapter(appAdapter);
        for(int i=0;i<candidates.size();i++)list.setItemChecked(i,selected.contains(candidates.get(i).packageName));
        list.setOnItemClickListener((parent,row,index,id)->{
            String packageName=shown.get(index).packageName;
            if(list.isItemChecked(index))selected.add(packageName);else selected.remove(packageName);
        });
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);
        RadioGroup scope=new RadioGroup(this);scope.setPadding(dp(12),dp(4),dp(12),0);
        RadioButton selectedMode=new RadioButton(this);selectedMode.setId(View.generateViewId());selectedMode.setText(R.string.vpn_scope_selected);scope.addView(selectedMode);
        RadioButton allMode=new RadioButton(this);allMode.setId(View.generateViewId());allMode.setText(R.string.vpn_scope_all);scope.addView(allMode);
        scope.check(AppSelection.allApplications(this)?allMode.getId():selectedMode.getId());panel.addView(scope);
        TextView scopeHelp=label(getString(R.string.vpn_scope_help),12,MUTED,false);scopeHelp.setPadding(dp(18),dp(4),dp(18),dp(8));panel.addView(scopeHelp);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint(R.string.ui_search_apps);search.setPadding(dp(18),dp(10),dp(18),dp(10));panel.addView(search);
        search.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence text,int start,int count,int after){}
            public void onTextChanged(CharSequence text,int start,int before,int count){
                String query=text.toString().trim().toLowerCase(Locale.ROOT);shown.clear();
                for(AppSelection.App app:candidates)if(app.label.toLowerCase(Locale.ROOT).contains(query)||app.packageName.toLowerCase(Locale.ROOT).contains(query))shown.add(app);
                list.clearChoices();appAdapter.notifyDataSetChanged();for(int i=0;i<shown.size();i++)list.setItemChecked(i,selected.contains(shown.get(i).packageName));
            }
            public void afterTextChanged(android.text.Editable text){}
        });
        panel.addView(list,new LinearLayout.LayoutParams(-1,(int)(getResources().getDisplayMetrics().heightPixels*0.42f)));
        scope.setOnCheckedChangeListener((group,checked)->{boolean all=checked==allMode.getId();search.setVisibility(all?View.GONE:View.VISIBLE);list.setVisibility(all?View.GONE:View.VISIBLE);});
        if(allMode.isChecked()){search.setVisibility(View.GONE);list.setVisibility(View.GONE);}
        if(candidates.isEmpty()){TextView none=label(s("apps_none"),14,MUTED,false);none.setPadding(dp(18),dp(4),dp(18),dp(8));panel.addView(none);}
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.ui_apps)
                .setView(panel)
                .setNegativeButton(s("cancel"),null)
                .setPositiveButton(s("save"),(choice,which) -> {
                    // Save first, then cancel an active or in-progress connection generation.
                    AppSelection.save(this,selected);
                    AppSelection.saveAllApplications(this,allMode.isChecked());
                    controller.disconnect();
                    showTab(selectedTab);
                    Toast.makeText(this,s("apps_saved"),Toast.LENGTH_LONG).show();
                }).create();
        dialog.show();
    }
    private final class AppChoiceRow extends LinearLayout implements Checkable {
        final ImageView icon;
        final TextView name,packageName;
        final CheckBox check;
        AppChoiceRow(){
            super(MainActivity.this);setOrientation(HORIZONTAL);setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(16),dp(10),dp(12),dp(10));setMinimumHeight(dp(72));
            setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
            icon=new ImageView(MainActivity.this);icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            addView(icon,new LinearLayout.LayoutParams(dp(42),dp(42)));
            LinearLayout labels=new LinearLayout(MainActivity.this);labels.setOrientation(VERTICAL);
            LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,-2,1);labelParams.setMarginStart(dp(14));
            addView(labels,labelParams);
            name=new TextView(MainActivity.this);name.setTextSize(16);name.setTextColor(NAVY);labels.addView(name);
            packageName=new TextView(MainActivity.this);packageName.setTextSize(11);packageName.setTextColor(MUTED);
            packageName.setMaxLines(1);packageName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);labels.addView(packageName);
            packageName.setVisibility(View.GONE);
            check=new CheckBox(MainActivity.this);check.setClickable(false);check.setFocusable(false);addView(check);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            setAccessibilityDelegate(new View.AccessibilityDelegate(){
                @Override public void onInitializeAccessibilityNodeInfo(View host,android.view.accessibility.AccessibilityNodeInfo info){
                    super.onInitializeAccessibilityNodeInfo(host,info);info.setCheckable(true);info.setChecked(isChecked());
                    info.setClassName(CheckBox.class.getName());
                }
            });
        }
        @Override public void setChecked(boolean checked){check.setChecked(checked);}
        @Override public boolean isChecked(){return check.isChecked();}
        @Override public void toggle(){setChecked(!isChecked());}
    }
    private void editAdvertisedHost(){EditText input=new EditText(this);input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setSingleLine(true);input.setHint(s("endpoint_hint"));input.setText(controller.snapshot().advertisedHost);input.setPadding(dp(20),dp(12),dp(20),dp(12));AlertDialog dialog=new AlertDialog.Builder(this).setTitle(s("endpoint_title")).setMessage(s("endpoint_help")).setView(input).setNegativeButton(s("cancel"),null).setPositiveButton(s("save"),null).create();dialog.setOnShowListener(v->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(w->{try{controller.setAdvertisedHost(input.getText().toString().trim());dialog.dismiss();Toast.makeText(this,s("saved"),Toast.LENGTH_SHORT).show();}catch(IllegalArgumentException e){input.setError(s("invalid_host"));}}));dialog.show();}
    private void chooseLanguage(){String[] names={s("system_language"),"English","Русский","中文","日本語","Français","Italiano","Español","Español (México)"};String[] tags={"","en","ru","zh","ja","fr","it","es","es-MX"};String current=getSharedPreferences("ui",MODE_PRIVATE).getString("language","");int checked=0;for(int i=0;i<tags.length;i++)if(tags[i].equals(current))checked=i;new AlertDialog.Builder(this).setTitle(s("language")).setSingleChoiceItems(names,checked,(dialog,which)->{getSharedPreferences("ui",MODE_PRIVATE).edit().putString("language",tags[which]).apply();dialog.dismiss();recreate();}).setNegativeButton(s("cancel"),null).show();}
}

