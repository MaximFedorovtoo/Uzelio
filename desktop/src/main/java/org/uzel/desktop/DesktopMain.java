package org.uzel.desktop;

import javax.swing.*;
import java.nio.channels.*;
import java.nio.file.*;

/** Native launcher. Directory participation never enables internet forwarding. */
public final class DesktopMain {
    public static Path dataDirectory(){
        String explicit=System.getProperty("uzelio.data",System.getProperty("peernet.data"));if(explicit!=null)return Path.of(explicit);
        // Keep the established state location so a renamed launcher retains identity and settings.
        String os=System.getProperty("os.name").toLowerCase(),base=os.contains("win")?System.getenv("LOCALAPPDATA"):null;
        if(base!=null)return Path.of(base,"PeerNet");
        if(os.contains("mac"))return Path.of(System.getProperty("user.home"),"Library","Application Support","PeerNet");
        String xdg=System.getenv("XDG_DATA_HOME");return Path.of(xdg==null?Path.of(System.getProperty("user.home"),".local","share").toString():xdg,"peernet");
    }
    public static java.awt.image.BufferedImage icon(){var image=new java.awt.image.BufferedImage(64,64,java.awt.image.BufferedImage.TYPE_INT_ARGB);var g=image.createGraphics();g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,java.awt.RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(DesktopArtwork.BG);g.fillRoundRect(0,0,64,64,16,16);DesktopArtwork.paint(g,"network",0,0,64,DesktopArtwork.CYAN);g.dispose();return image;}
    public static void main(String[] args)throws Exception {
        if(args.length==1&&args[0].equals("--install-routing")){WindowsRoutingSetup.install();System.out.println("Uzelio Windows routing component installed");return;}
        Path root=dataDirectory();Files.createDirectories(root);
        if(args.length>=1&&args[0].equals("--relay")){
            int port=args.length>1?Integer.parseInt(args[1]):39284;
            Path relayRoot=root.resolve("relay");Files.createDirectories(relayRoot);
            var identity=IdentityStore.load(relayRoot);
            try(var relay=new org.uzel.messenger.relay.RelayServer(identity,java.net.InetAddress.getByName("0.0.0.0"),port)){
                String host=org.uzel.messenger.chat.LocalAddress.preferred();
                System.out.println("Uzelio relay слушает TCP "+relay.port());
                System.out.println("relay="+(host.isEmpty()?"<публичный-адрес>":host)+":"+relay.port()+":"+relay.pin());
                System.out.println("Этот адрес вставьте в приглашение. Нужен доступный извне TCP-порт. Ctrl+C — остановить.");
                new java.util.concurrent.CountDownLatch(1).await();
            }
            return;
        }
        FileChannel channel=FileChannel.open(root.resolve("instance.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock;
        try{lock=channel.tryLock();}catch(OverlappingFileLockException e){lock=null;}
        if(lock==null){channel.close();JOptionPane.showMessageDialog(null,"Uzelio уже запущен. Откройте значок в области уведомлений.");return;}
        Runtime.getRuntime().addShutdownHook(new Thread(()->{try{channel.close();}catch(Exception ignored){}}));
        DesktopBackend backend=new FederationBackend(root,IdentityStore.load(root));Runtime.getRuntime().addShutdownHook(new Thread(backend::close));
        if(args.length==0||args[0].equals("--smoke-test")){org.uzel.desktop.messenger.DesktopMessenger.start(root);if(args.length==0)org.uzel.desktop.messenger.DesktopMessenger.chats(root);}
        if(args.length>0&&args[0].equals("--smoke-test")){backend.start(new DesktopConfig("localhost",17777,true));System.out.println("Uzelio runtime OK; identity persisted; network started");backend.close();return;}
        if(args.length==2&&args[0].equals("--render-preview")){SwingUtilities.invokeAndWait(()->{try{new DesktopWindow(root,backend).renderPreview(Path.of(args[1]));}catch(Exception e){throw new RuntimeException(e);}});backend.close();return;}
        SwingUtilities.invokeLater(()->{try{new DesktopWindow(root,backend).open();}catch(Exception e){JOptionPane.showMessageDialog(null,e.getMessage(),"Uzelio",JOptionPane.ERROR_MESSAGE);System.exit(1);}});
    }
}
