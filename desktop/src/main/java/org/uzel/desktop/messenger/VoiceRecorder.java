package org.uzel.desktop.messenger;

import javax.sound.sampled.*;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Records a 16 kHz / 16-bit / mono PCM voice note and delivers it as a self-contained WAV. */
final class VoiceRecorder {
    /** Exactly one callback per recorder, always from the capture thread. */
    @FunctionalInterface interface Listener { void finished(VoiceRecorder source,byte[] wav,String error); }
    static final int RATE=16000,BITS=16,CHANNELS=1,MAX_SECONDS=120;
    private static final int MAX_PCM=RATE*(BITS/8)*CHANNELS*MAX_SECONDS;
    private final Listener listener;
    private final AtomicBoolean finished=new AtomicBoolean();
    private volatile TargetDataLine line;
    private volatile boolean running;
    VoiceRecorder(Listener listener){this.listener=listener;}
    /** Opens the microphone synchronously so the caller can report a missing device at once. */
    void start()throws Exception{
        AudioFormat format=new AudioFormat(RATE,BITS,CHANNELS,true,false);
        TargetDataLine opened=AudioSystem.getTargetDataLine(format);
        if(opened==null)throw new LineUnavailableException("Microphone is not available");
        try{opened.open(format,RATE*(BITS/8)*CHANNELS);}
        catch(Exception e){try{opened.close();}catch(RuntimeException ignored){}throw e;}
        line=opened;running=true;
        opened.start();
        Thread capture=new Thread(this::capture,"uzelio-voice-recorder");
        capture.setDaemon(true);
        capture.start();
    }
    /** Ends capture; the listener receives the WAV from the capture thread. */
    void stop(){
        running=false;
        TargetDataLine current=line;
        if(current!=null){try{current.stop();}catch(RuntimeException ignored){}try{current.close();}catch(RuntimeException ignored){}}
    }
    private void capture(){
        ByteArrayOutputStream pcm=new ByteArrayOutputStream();
        byte[] buffer=new byte[4096];
        int total=0;
        try{
            TargetDataLine current=line;
            if(current==null)throw new IllegalStateException("Microphone is not open");
            while(running&&total<MAX_PCM){
                int read=current.read(buffer,0,Math.min(buffer.length,MAX_PCM-total));
                if(read<=0)break;
                pcm.write(buffer,0,read);
                total+=read;
            }
        }catch(Exception closed){/* A stopped or unavailable line ends the note. */}
        finally{
            running=false;
            TargetDataLine current=line;
            if(current!=null){try{current.stop();}catch(RuntimeException ignored){}try{current.close();}catch(RuntimeException ignored){}}
            finish(pcm.toByteArray());
        }
    }
    private void finish(byte[] pcm){
        if(!finished.compareAndSet(false,true))return;
        if(pcm.length==0){listener.finished(this,null,"Запись пуста");return;}
        try{listener.finished(this,wav(pcm),null);}
        catch(RuntimeException failed){try{listener.finished(this,null,"Запись не удалась");}catch(RuntimeException ignored){/* The UI callback must never break capture. */}}
    }
    /** Canonical 44-byte RIFF/WAVE header followed by raw PCM. */
    static byte[] wav(byte[] pcm){
        int blockAlign=CHANNELS*(BITS/8),byteRate=RATE*blockAlign,size=pcm.length;
        byte[] out=new byte[44+size];
        ascii(out,0,"RIFF");int32(out,4,36+size);ascii(out,8,"WAVE");
        ascii(out,12,"fmt ");int32(out,16,16);int16(out,20,1);int16(out,22,CHANNELS);
        int32(out,24,RATE);int32(out,28,byteRate);int16(out,32,blockAlign);int16(out,34,BITS);
        ascii(out,36,"data");int32(out,40,size);
        System.arraycopy(pcm,0,out,44,size);
        return out;
    }
    private static void ascii(byte[] out,int at,String value){for(int i=0;i<value.length();i++)out[at+i]=(byte)value.charAt(i);}
    private static void int32(byte[] out,int at,int value){out[at]=(byte)value;out[at+1]=(byte)(value>>>8);out[at+2]=(byte)(value>>>16);out[at+3]=(byte)(value>>>24);}
    private static void int16(byte[] out,int at,int value){out[at]=(byte)value;out[at+1]=(byte)(value>>>8);}
}
