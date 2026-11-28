package org.veilocity.arkhive;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import org.w3c.dom.*;

/** Bounded, offline, read-only previews. Original source files are never changed. */
final class OfficeViewer {
  private OfficeViewer(){}
  static String esc(String s){return XlsxViewer.esc(s);}
  static byte[] read(InputStream in,int limit)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] tmp=new byte[8192];int n;while((n=in.read(tmp))!=-1){if(b.size()+n>limit)throw new IOException("Document exceeds preview size limit");b.write(tmp,0,n);}return b.toByteArray();}
  static String shell(String name,String type,String content){return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes'><style>*{box-sizing:border-box}body{margin:0;background:#f8ead8;color:#51473c;font:16px/1.55 system-ui;padding:22px;overflow-wrap:anywhere}main{max-width:920px;margin:auto}h1{font-size:1.3rem;color:#307b78;overflow-wrap:anywhere}article{background:#fff9f0;border:1px solid #e8d4bd;border-radius:14px;padding:18px}p{margin:8px 0}table{border-collapse:collapse;width:100%}td{border:1px solid #d6c7b7;padding:6px;vertical-align:top}header{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin-bottom:12px}button{border:0;border-radius:8px;background:#307b78;color:white;padding:8px 12px;font-weight:bold}small{color:#756b60}blockquote{border-left:3px solid #307b78;padding-left:12px}pre{white-space:pre-wrap;overflow-wrap:anywhere}</style></head><body><main><h1>"+esc(name)+"</h1><header><small>Offline · Read-only "+esc(type)+" preview</small><button onclick='document.getElementById(\"page\").style.fontSize=(parseFloat(getComputedStyle(document.getElementById(\"page\")).fontSize)*1.15)+\"px\"'>＋</button><button onclick='document.getElementById(\"page\").style.fontSize=(parseFloat(getComputedStyle(document.getElementById(\"page\")).fontSize)/1.15)+\"px\"'>－</button></header><article id='page'>"+content+"</article></main></body></html>";}
  static String docx(InputStream input,String name)throws Exception {
    byte[] data=read(input,24*1024*1024);byte[] document=null;long total=0;
    try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(data))){ZipEntry e;int entries=0;while((e=zip.getNextEntry())!=null){if(++entries>1500)throw new IOException("Too many document entries");if(e.getName().equals("word/document.xml")){document=read(zip,8*1024*1024);break;}if(e.getSize()>0){total+=e.getSize();if(total>48*1024*1024)throw new IOException("Document too large");}}}
    if(document==null)throw new IOException("Word document XML not found");
    Document dom=XlsxViewer.xml(document);NodeList paragraphs=dom.getElementsByTagNameNS("*","p");StringBuilder html=new StringBuilder();int count=0;
    for(int i=0;i<paragraphs.getLength()&&count<25000;i++){Element p=(Element)paragraphs.item(i);if(hasAncestor(p,"p"))continue;StringBuilder line=new StringBuilder();NodeList nodes=p.getElementsByTagNameNS("*","t");for(int j=0;j<nodes.getLength();j++)line.append(nodes.item(j).getTextContent());if(line.length()>0){html.append("<p>").append(esc(line.toString())).append("</p>");count++;}}
    if(html.length()==0)html.append("<p>No previewable text found.</p>");
    return shell(name,"DOCX",html.toString());
  }
  static boolean hasAncestor(Node n,String local){Node p=n.getParentNode();while(p!=null){if(local.equals(p.getLocalName()))return true;p=p.getParentNode();}return false;}
  static int i32(byte[] b,int off){return ByteBuffer.wrap(b,off,4).order(ByteOrder.LITTLE_ENDIAN).getInt();}
  static int u16(byte[] b,int off){return (b[off]&255)|((b[off+1]&255)<<8);}
  static long i64(byte[] b,int off){return ByteBuffer.wrap(b,off,8).order(ByteOrder.LITTLE_ENDIAN).getLong();}
  static class Entry{String name;int type,start;long size;}
  static class Ole {
    final byte[] b;final int sector,miniSector;final int[] fat,miniFat;final List<Entry> entries=new ArrayList<>();final byte[] rootStream;
    Ole(byte[] bytes)throws IOException{
      b=bytes;if(b.length<512||b[0]!=(byte)0xd0||b[1]!=(byte)0xcf||b[2]!=0x11||b[3]!=(byte)0xe0)throw new IOException("Not a supported Outlook MSG / Word DOC file");
      sector=1<<u16(b,30);miniSector=1<<u16(b,32);if(sector!=512&&sector!=4096)throw new IOException("Unsupported OLE sector size");
      int fatCount=i32(b,44),dif=i32(b,68),difCount=i32(b,72);List<Integer> ids=new ArrayList<>();for(int j=0;j<109;j++){int id=i32(b,76+j*4);if(id>=0)ids.add(id);}
      for(int n=0;n<difCount&&dif>=0&&n<1000;n++){int off=offset(dif);for(int j=0;j<sector/4-1;j++){int id=i32(b,off+j*4);if(id>=0)ids.add(id);}dif=i32(b,off+sector-4);}
      fat=new int[Math.min(fatCount,ids.size())*sector/4];int k=0;for(int j=0;j<Math.min(fatCount,ids.size());j++){int off=offset(ids.get(j));for(int q=0;q<sector/4;q++)fat[k++]=i32(b,off+q*4);}
      byte[] dir=chain(i32(b,48),fat,12*1024*1024,sector,b);for(int pos=0;pos+128<=dir.length;pos+=128){int len=u16(dir,pos+64);if(len<2||len>64)continue;Entry e=new Entry();e.name=new String(dir,pos,len-2,StandardCharsets.UTF_16LE);e.type=dir[pos+66]&255;e.start=i32(dir,pos+116);e.size=i64(dir,pos+120);entries.add(e);}
      Entry root=null;for(Entry e:entries)if(e.type==5){root=e;break;}if(root==null)throw new IOException("Missing OLE root");
      rootStream=chain(root.start,fat,32*1024*1024,sector,b);
      int mfStart=i32(b,60),mfCount=i32(b,64);byte[] mf=mfCount>0?chain(mfStart,fat,4*1024*1024,sector,b):new byte[0];miniFat=new int[mf.length/4];for(int j=0;j<miniFat.length;j++)miniFat[j]=i32(mf,j*4);
    }
    int offset(int id)throws IOException{long p=512L+(long)id*sector;if(id<0||p+sector>b.length)throw new IOException("Invalid OLE sector");return (int)p;}
    byte[] chain(int start,int[] map,int max,int size,byte[] source)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();Set<Integer> visited=new HashSet<>();int id=start;while(id>=0&&id<map.length&&visited.add(id)){long pos=(source==b?512L:0)+(long)id*size;if(pos<0||pos+size>source.length)throw new IOException("Invalid OLE stream offset");if(out.size()+size>max)throw new IOException("OLE stream exceeds limit");out.write(source,(int)pos,size);id=map[id];}return out.toByteArray();}
    byte[] get(Entry e)throws IOException{if(e.size<0||e.size>12*1024*1024)throw new IOException("OLE stream too large");if(e.size==0)return new byte[0];byte[] raw=e.size<4096?chain(e.start,miniFat,12*1024*1024,miniSector,rootStream):chain(e.start,fat,12*1024*1024,sector,b);return Arrays.copyOf(raw,(int)Math.min(e.size,raw.length));}
    byte[] named(String name)throws IOException{for(Entry e:entries)if(e.type==2&&e.name.equalsIgnoreCase(name))return get(e);return null;}
  }
  static String msg(InputStream in,String name)throws Exception{
    Ole ole=new Ole(read(in,24*1024*1024));StringBuilder out=new StringBuilder();String[][] fields={{"Subject","0037"},{"From","0C1A"},{"To","0E04"},{"Cc","0E03"},{"Date","0039"}};
    for(String[] f:fields){String value=property(ole,f[1]);if(value!=null&&!value.trim().isEmpty())out.append("<p><b>").append(f[0]).append(":</b> ").append(esc(value)).append("</p>");}
    String body=property(ole,"1000");if(body!=null)out.append("<hr><div style='white-space:pre-wrap'>").append(esc(body)).append("</div>");
    else out.append("<p>Plain-text email body unavailable in this message.</p>");
    List<String> attachments=new ArrayList<>();for(Entry e:ole.entries)if(e.name.startsWith("__substg1.0_3707")&&e.type==2){byte[] data=ole.get(e);String n=decode(data,e.name);if(!n.isEmpty())attachments.add(n);}
    if(!attachments.isEmpty()){out.append("<h3>Attachment names (read-only)</h3><ul>");for(String n:attachments)out.append("<li>").append(esc(n)).append("</li>");out.append("</ul><small>Embedded attachments are listed but not extracted in this initial viewer.</small>");}
    return shell(name,"Outlook MSG",out.toString());
  }
  static String property(Ole ole,String id)throws IOException{String[] suffixes={"__substg1.0_"+id+"001F","__substg1.0_"+id+"001E"};for(String suffix:suffixes){byte[] data=ole.named(suffix);if(data!=null)return decode(data,suffix);}return null;}
  static String decode(byte[] data,String key){String s=key.endsWith("001F")?new String(data,StandardCharsets.UTF_16LE):new String(data,StandardCharsets.ISO_8859_1);return s.replace("\u0000","").trim();}
  static String doc(InputStream in,String name)throws Exception{
    Ole ole=new Ole(read(in,24*1024*1024));byte[] word=ole.named("WordDocument");if(word==null)throw new IOException("Legacy Word document stream not found");
    // Binary .doc requires a full piece-table parser; never present guessed text as reliable document content.
    return shell(name,"legacy DOC","<p>This is a valid legacy Microsoft Word binary document. Accurate text extraction requires a Word 97–2003 piece-table parser, which is not yet included. The original file is unchanged.</p>");
  }
}
