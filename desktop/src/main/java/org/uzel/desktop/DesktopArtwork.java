package org.uzel.desktop;

import java.awt.*;
import java.awt.geom.*;

/** Original PeerArtwork shapes translated from Android Canvas to Java2D, Ocean 2.0 palette. */
final class DesktopArtwork {
    // Ocean 2.0 tokens: docs/design-tokens-2026.md.
    static final Color VOID=new Color(0x04090F),BG=new Color(0x070E17),BG2=new Color(0x0A1420),
        SURFACE=new Color(0x0E1E2E),SURFACE2=new Color(0x122639),RAISED=new Color(0x163149),
        LINE=new Color(148,190,222,36),LINE_STRONG=new Color(148,190,222,66),
        CYAN=new Color(0x38F0DD),ACCENT_DEEP=new Color(0x1CC7D2),MINT=new Color(0x7CF6C8),
        IRIS=new Color(0x8F7CFF),AMBER=new Color(0xFFC978),CORAL=new Color(0xFF8E9A),
        WHITE=new Color(0xEAF6FF),MUTED=new Color(0x93ADC4),MUTED2=new Color(0x6F8BA3);
    static void paint(Graphics2D source,String kind,double x,double y,double side,Color tint) {
        Graphics2D g=(Graphics2D)source.create();g.translate(x,y);g.scale(side/100,side/100);g.setColor(tint);g.setStroke(new BasicStroke(6,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));Path2D p=new Path2D.Double();
        switch(kind) {
            case "network" -> {
                g.setPaint(new RadialGradientPaint(50,50,49,new float[]{0,.58f,1},new Color[]{alpha(tint,72),alpha(tint,22),alpha(tint,0)}));g.fill(new Ellipse2D.Double(1,1,98,98));
                g.setPaint(new RadialGradientPaint(34,29,78,new float[]{0,.52f,1},new Color[]{new Color(0x1E4B57),new Color(0x113442),new Color(0x07131F)}));g.fill(new Ellipse2D.Double(13.5,13.5,73,73));
                p.moveTo(60,15);p.curveTo(78,20,89,35,85,54);p.curveTo(81,72,67,85,48,87);p.curveTo(63,72,68,57,64,43);p.curveTo(61,31,55,22,60,15);p.closePath();g.setColor(new Color(4,9,15,96));g.fill(p);
                g.setColor(alpha(tint,88));g.setStroke(new BasicStroke(1.15f));g.draw(new Ellipse2D.Double(13.5,13.5,73,73));g.draw(new Ellipse2D.Double(31,13.5,38,73));g.draw(new Ellipse2D.Double(13.5,31,73,38));
                p.reset();p.moveTo(20,29);p.quadTo(50,43,80,29);p.moveTo(20,71);p.quadTo(50,57,80,71);g.draw(p);
                p.reset();p.moveTo(50,18);p.lineTo(74,34);p.lineTo(71,63);p.lineTo(50,81);p.lineTo(28,64);p.lineTo(26,36);p.closePath();p.moveTo(50,18);p.lineTo(71,63);p.moveTo(74,34);p.lineTo(28,64);p.moveTo(26,36);p.lineTo(71,63);g.setColor(alpha(tint,184));g.setStroke(new BasicStroke(1.7f));g.draw(p);
                for(int[] q:new int[][]{{50,18},{74,34},{71,63},{50,81},{28,64},{26,36}}){g.setColor(alpha(tint,30));g.fill(new Ellipse2D.Double(q[0]-9,q[1]-9,18,18));g.setColor(alpha(tint,105));g.fill(new Ellipse2D.Double(q[0]-5,q[1]-5,10,10));g.setColor(tint);g.fill(new Ellipse2D.Double(q[0]-3.2,q[1]-3.2,6.4,6.4));g.setColor(new Color(255,255,255,180));g.fill(new Ellipse2D.Double(q[0]-1.8,q[1]-1.8,2.2,2.2));}
                p.reset();p.moveTo(32,22);p.quadTo(40,17,47,16);g.setColor(new Color(255,255,255,128));g.draw(p);
            }
            case "home" -> {p.moveTo(17,47);p.lineTo(50,20);p.lineTo(83,47);p.moveTo(25,42);p.lineTo(25,83);p.lineTo(75,83);p.lineTo(75,42);p.moveTo(45,83);p.lineTo(45,61);p.moveTo(55,83);p.lineTo(55,61);g.draw(p);}
            case "map" -> {p.moveTo(50,86);p.curveTo(44,77,25,56,25,40);p.curveTo(25,24,36,14,50,14);p.curveTo(64,14,75,24,75,40);p.curveTo(75,56,56,77,50,86);p.closePath();g.fill(p);g.setColor(BG);g.fill(new Ellipse2D.Double(42,32,16,16));}
            case "traffic" -> {for(int i=0;i<3;i++)g.fill(new RoundRectangle2D.Double(18+i*24,55-i*18,16,27+i*18,5,5));}
            case "profile" -> {g.fill(new Ellipse2D.Double(34,18,32,32));g.fill(new RoundRectangle2D.Double(22,53,56,34,18,18));}
            case "settings" -> {for(int i=0;i<32;i++){double a=-Math.PI/2+Math.PI*2*i/32,r=i%4<2?34:27;double px=50+Math.cos(a)*r,py=50+Math.sin(a)*r;if(i==0)p.moveTo(px,py);else p.lineTo(px,py);}p.closePath();g.fill(p);g.setColor(BG);g.fill(new Ellipse2D.Double(38,38,24,24));}
            case "apps" -> {for(int i=0;i<4;i++)g.fill(new RoundRectangle2D.Double(19+(i%2)*38,19+(i/2)*38,24,24,7,7));}
            case "back" -> {p.moveTo(56,22);p.lineTo(28,50);p.lineTo(56,78);p.moveTo(30,50);p.lineTo(78,50);g.draw(p);}
            case "chevron" -> {p.moveTo(37,24);p.lineTo(63,50);p.lineTo(37,76);g.draw(p);}
            case "offline","search","loading" -> {g.setPaint(new RadialGradientPaint(50,50,46,new float[]{0,1},new Color[]{alpha(tint,38),alpha(tint,0)}));g.fill(new Ellipse2D.Double(4,4,92,92));g.setColor(alpha(tint,100));g.setStroke(new BasicStroke(1.5f));g.draw(new Ellipse2D.Double(6,6,88,88));g.setStroke(new BasicStroke(4.5f,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));g.setColor(tint);if(kind.equals("offline")){g.draw(new Arc2D.Double(21,17,58,58,45,90,Arc2D.OPEN));g.draw(new Arc2D.Double(31,27,38,38,45,90,Arc2D.OPEN));g.fill(new Ellipse2D.Double(47,71,6,6));g.drawLine(26,25,75,75);}else{g.draw(new Ellipse2D.Double(27,27,34,34));g.drawLine(57,57,76,76);}}
        }g.dispose();
    }
    /** Soft radial light used behind status dots and primary markers. */
    static void dotGlow(Graphics2D g,float cx,float cy,float radius,Color tint,int strength){
        float r=Math.max(1,radius);
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx,cy),r,new float[]{0,.45f,1},new Color[]{alpha(tint,strength),alpha(tint,strength/3),alpha(tint,0)}));
        g.fill(new Ellipse2D.Float(cx-r,cy-r,r*2,r*2));
    }
    /** Display type with letter tracking; Swing fonts have no tracking property. */
    static void tracked(Graphics2D g,String value,int x,int y,int size,Color color,boolean bold,float spacing){
        g.setFont(new Font("Segoe UI",bold?Font.BOLD:Font.PLAIN,size));g.setColor(color);
        FontMetrics fm=g.getFontMetrics();float cursor=x;
        for(int index=0;index<value.length();index++){
            String glyph=String.valueOf(value.charAt(index));
            g.drawString(glyph,Math.round(cursor),y);
            cursor+=fm.stringWidth(glyph)+spacing;
        }
    }
    static float trackedWidth(Graphics2D g,String value,int size,boolean bold,float spacing){
        g.setFont(new Font("Segoe UI",bold?Font.BOLD:Font.PLAIN,size));
        FontMetrics fm=g.getFontMetrics();float width=0;
        for(int index=0;index<value.length();index++)width+=fm.stringWidth(String.valueOf(value.charAt(index)))+spacing;
        return Math.max(0,width-spacing);
    }
    static Color alpha(Color c,int alpha){return new Color(c.getRed(),c.getGreen(),c.getBlue(),alpha);}
}
