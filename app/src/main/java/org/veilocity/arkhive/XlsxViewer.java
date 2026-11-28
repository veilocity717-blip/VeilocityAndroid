package org.veilocity.arkhive;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

/** Read-only, fully offline OOXML worksheet preview. No third-party dependencies. */
final class XlsxViewer {
  private static final int MAX_ENTRY=7*1024*1024, MAX_TOTAL=24*1024*1024;
  private static final int MAX_ROWS=1000, MAX_COLS=80, MAX_CELLS=50000, MAX_SHEETS=12, MAX_RENDERED=120000;
  private XlsxViewer(){}
  static String esc(String s){if(s==null)return "";return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
  // Android's platform XML parser does not implement every Apache Xerces feature.
  // Reject DTD declarations before parsing, and disable external resolution on
  // supported implementations without treating unsupported optional flags as fatal.
  static void optionalFeature(DocumentBuilderFactory factory,String name,boolean enabled) {
    try { factory.setFeature(name,enabled); }
    catch (javax.xml.parsers.ParserConfigurationException | AbstractMethodError ignored) { }
  }
  static void rejectDoctype(byte[] bytes)throws IOException {
    // OOXML XML parts are normally UTF-8, but UTF-16 is also legal.
    // Check UTF-8/ASCII and both UTF-16 byte orders for a DTD opener.
    String ascii=new String(bytes,StandardCharsets.ISO_8859_1).toUpperCase(Locale.ROOT);
    if(ascii.contains("<!DOCTYPE"))throw new IOException("DTD declarations are not allowed in XLSX files");
    if(bytes.length>=2 && ((bytes[0]&255)==255&&(bytes[1]&255)==254 ||
                           (bytes[0]&255)==254&&(bytes[1]&255)==255 ||
                           (bytes[0]==0 && bytes[1]=='<') || (bytes[0]=='<' && bytes[1]==0))) {
      String utf16=new String(bytes, ((bytes[0]&255)==255&&(bytes[1]&255)==254 || bytes[0]=='<')
          ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE).toUpperCase(Locale.ROOT);
      if(utf16.contains("<!DOCTYPE"))throw new IOException("DTD declarations are not allowed in XLSX files");
    }
  }
  static Document xml(byte[] bytes)throws Exception {
    rejectDoctype(bytes);
    DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    optionalFeature(f,"http://apache.org/xml/features/disallow-doctype-decl",true);
    optionalFeature(f,"http://xml.org/sax/features/external-general-entities",false);
    optionalFeature(f,"http://xml.org/sax/features/external-parameter-entities",false);
    optionalFeature(f,"http://apache.org/xml/features/nonvalidating/load-external-dtd",false);
    try { f.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) { }
    f.setExpandEntityReferences(false);
    DocumentBuilder builder=f.newDocumentBuilder();
    builder.setEntityResolver((publicId,systemId)->{
      throw new org.xml.sax.SAXException("External XML entities are not permitted");
    });
    Document document=builder.parse(new ByteArrayInputStream(bytes));
    if(document.getDoctype()!=null)throw new IOException("DTD declarations are not allowed in XLSX files");
    return document;
  }
  static List<Element> children(Element e,String name){List<Element> result=new ArrayList<>();NodeList ns=e.getChildNodes();for(int i=0;i<ns.getLength();i++){Node n=ns.item(i);if(n instanceof Element && name.equals(n.getLocalName()))result.add((Element)n);}return result;}
  static Element child(Element e,String name){for(Element n:children(e,name))return n;return null;}
  static String text(Element e,String name){Element n=child(e,name);return n==null?"":n.getTextContent();}
  static int integer(String value,int fallback){try{return Integer.parseInt(value);}catch(Exception ignored){return fallback;}}
  static int colIndex(String ref){int col=0;for(int i=0;i<ref.length();i++){char c=ref.charAt(i);if(c>='A'&&c<='Z')col=col*26+c-'A'+1;else if(c>='a'&&c<='z')col=col*26+c-'a'+1;else break;}return col-1;}
  static String colLabel(int c){String s="";for(int n=c+1;n>0;n=(n-1)/26)s=(char)('A'+(n-1)%26)+s;return s;}
  static String normalizeTarget(String t){if(t.startsWith("/"))t=t.substring(1);else t="xl/"+t;Deque<String> p=new ArrayDeque<>();for(String part:t.split("/")){if(part.equals("..")){if(!p.isEmpty())p.removeLast();}else if(!part.equals(".")&&!part.isEmpty())p.addLast(part);}return String.join("/",p);}
  static String cellValue(Element c,List<String> strings){String type=c.getAttribute("t");String raw=text(c,"v");if("inlineStr".equals(type)){Element is=child(c,"is");return is==null?"":is.getTextContent();}if("s".equals(type)){int i=integer(raw,-1);return i>=0&&i<strings.size()?strings.get(i):"";}if("b".equals(type))return "1".equals(raw)?"TRUE":"FALSE";if("e".equals(type))return raw; if("str".equals(type))return raw;return raw;}
  static String page(InputStream input,String filename)throws Exception {
    Map<String,byte[]> entries=new HashMap<>();int total=0;
    try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(input))){ZipEntry e;byte[] buf=new byte[8192];while((e=zip.getNextEntry())!=null){String name=e.getName();if(e.isDirectory()||!name.startsWith("xl/")||!(name.equals("xl/workbook.xml")||name.equals("xl/_rels/workbook.xml.rels")||name.equals("xl/sharedStrings.xml")||name.startsWith("xl/worksheets/sheet")&&name.endsWith(".xml")))continue;ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=zip.read(buf))!=-1){if(out.size()+n>MAX_ENTRY||total+out.size()+n>MAX_TOTAL)throw new IOException("Workbook too large for the lightweight offline viewer");out.write(buf,0,n);}byte[] data=out.toByteArray();total+=data.length;entries.put(name,data);}}
    if(!entries.containsKey("xl/workbook.xml"))throw new IOException("Not a supported XLSX workbook");
    List<String> shared=new ArrayList<>();if(entries.containsKey("xl/sharedStrings.xml")){Document ss=xml(entries.get("xl/sharedStrings.xml"));NodeList items=ss.getElementsByTagNameNS("*","si");for(int i=0;i<items.getLength()&&i<200000;i++)shared.add(items.item(i).getTextContent());}
    Map<String,String> targets=new HashMap<>();if(entries.containsKey("xl/_rels/workbook.xml.rels")){Document rels=xml(entries.get("xl/_rels/workbook.xml.rels"));NodeList links=rels.getElementsByTagNameNS("*","Relationship");for(int i=0;i<links.getLength();i++){Element e=(Element)links.item(i);if(e.getAttribute("Type").endsWith("/worksheet")&&!"External".equals(e.getAttribute("TargetMode")))targets.put(e.getAttribute("Id"),normalizeTarget(e.getAttribute("Target")));}}
    Document book=xml(entries.get("xl/workbook.xml"));NodeList sheets=book.getElementsByTagNameNS("*","sheet");
    StringBuilder html=new StringBuilder("<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Spreadsheet viewer</title><style>"+
      "*{box-sizing:border-box}body{margin:0;background:#f8ead8;color:#51473c;font:15px system-ui,sans-serif}.head{padding:18px 16px 10px;background:#fff4e6}h1{font-size:1.15rem;color:#307b78;overflow-wrap:anywhere;margin:0 0 7px}.sub{font-size:.82rem;color:#766d63}.zoomtools{display:flex;align-items:center;gap:8px;padding:9px 12px;background:#fff4e6;flex-wrap:wrap}.zoomtools button{border:1px solid #c7d8d3;background:white;color:#307b78;border-radius:12px;min-width:42px;min-height:40px;font-size:17px;font-weight:700}.zoomtools .reset{font-size:13px;padding:0 12px}.zoomtools input{flex:1;min-width:90px;accent-color:#307b78}.zoomvalue{font-weight:700;color:#307b78;min-width:48px;text-align:center}.tabs{display:flex;overflow-x:auto;gap:6px;padding:12px;background:#fff4e6}.tabs button{border:1px solid #c7d8d3;border-radius:16px;padding:9px 14px;background:white;color:#307b78;white-space:nowrap;font-weight:600}.tabs button.active{background:#307b78;color:white}.sheet{display:none;padding:0 10px 16px}.sheet.active{display:block}.scroll{overflow:auto;max-height:calc(100vh - 180px);border:1px solid #d9cbb8;border-radius:10px;background:white}table{border-collapse:separate;border-spacing:0;min-width:max-content;font-size:13px}td,th{border-right:1px solid #e6e1d8;border-bottom:1px solid #e6e1d8;padding:7px 10px;min-width:92px;max-width:350px;white-space:pre-wrap;overflow-wrap:anywhere;vertical-align:top}th{position:sticky;top:0;background:#e7f1ee;color:#307b78;z-index:2}.rowhead{position:sticky;left:0;background:#f6f1e8;min-width:44px;z-index:1;color:#786f65;text-align:center}.corner{left:0;z-index:3}.notice{margin:12px 16px;padding:10px;border-radius:10px;background:#fff6e9;color:#7d6b54;font-size:.83rem}</style></head><body>");
    html.append("<div class='head'><h1>📊 ").append(esc(filename)).append("</h1><div class='sub'>Offline · Read-only XLSX preview</div></div><div class='zoomtools' aria-label='Spreadsheet magnification'><button type='button' id='zoomout' aria-label='Zoom out'>−</button><input type='range' id='zoomrange' min='50' max='200' step='10' value='100' aria-label='Zoom level'><span class='zoomvalue' id='zoomvalue'>100%</span><button type='button' id='zoomin' aria-label='Zoom in'>+</button><button type='button' id='zoomreset' class='reset'>Reset</button></div><div class='tabs'>");
    int shown=0;StringBuilder content=new StringBuilder();int cells=0, rendered=0;boolean truncated=false;
    for(int si=0;si<sheets.getLength()&&shown<MAX_SHEETS;si++){
      Element sh=(Element)sheets.item(si);String id=sh.getAttributeNS("http://schemas.openxmlformats.org/officeDocument/2006/relationships","id");if(id.isEmpty())id=sh.getAttribute("r:id");String target=targets.get(id);if(target==null)target="xl/worksheets/sheet"+(si+1)+".xml";byte[] sheetBytes=entries.get(target);if(sheetBytes==null)continue;
      String sheetName=sh.getAttribute("name");int sheetIndex=shown++;
      html.append("<button type='button' data-sheet='").append(sheetIndex).append("' class='").append(sheetIndex==0?"active":"").append("'>").append(esc(sheetName)).append("</button>");
      content.append("<section class='sheet ").append(sheetIndex==0?"active":"").append("' id='sheet").append(sheetIndex).append("'><div class='scroll'><table><thead><tr><th class='rowhead corner'>#</th>");
      Document doc=xml(sheetBytes);NodeList rows=doc.getElementsByTagNameNS("*","row");int maxCol=0;
      for(int ri=0;ri<rows.getLength()&&ri<MAX_ROWS;ri++){Element row=(Element)rows.item(ri);for(Element c:children(row,"c")){int ci=colIndex(c.getAttribute("r"));if(ci>=0&&ci<MAX_COLS)maxCol=Math.max(maxCol,ci+1);}}
      maxCol=Math.max(1,maxCol);for(int ci=0;ci<maxCol;ci++)content.append("<th>").append(colLabel(ci)).append("</th>");content.append("</tr></thead><tbody>");
      int displayed=0;
      for(int ri=0;ri<rows.getLength()&&displayed<MAX_ROWS&&cells<MAX_CELLS&&rendered+maxCol<=MAX_RENDERED;ri++){
        Element row=(Element)rows.item(ri);int rowNo=integer(row.getAttribute("r"),ri+1);if(rowNo>1000000)continue;
        String[] values=new String[maxCol];int sequential=0;
        for(Element c:children(row,"c")){int ci=colIndex(c.getAttribute("r"));if(ci<0)ci=sequential;sequential=ci+1;if(ci>=0&&ci<maxCol){values[ci]=cellValue(c,shared);cells++;if(cells>=MAX_CELLS){truncated=true;break;}}}
        content.append("<tr><td class='rowhead'>").append(rowNo).append("</td>");for(String value:values)content.append("<td>").append(esc(value)).append("</td>");content.append("</tr>");displayed++;rendered+=maxCol;
      }
      if(rows.getLength()>MAX_ROWS||cells>=MAX_CELLS||rendered+maxCol>MAX_RENDERED)truncated=true;
      content.append("</tbody></table></div></section>");
    }
    html.append("</div>").append(content);
    if(shown==0)html.append("<div class='notice'>No readable worksheets were found.</div>");
    if(truncated||sheets.getLength()>MAX_SHEETS)html.append("<div class='notice'>Preview limited to 12 sheets, 1,000 rows per sheet, 80 columns, 50,000 populated cells and 120,000 displayed cells. The original workbook is unchanged.</div>");
    html.append("<div class='notice'>Stored cell values are shown. Formulas are not recalculated; Excel charts, merged-cell layouts and full formatting are not reproduced.</div>");
    html.append("<script>(function(){var level=100,range=document.getElementById('zoomrange'),label=document.getElementById('zoomvalue');"+
      "function setZoom(v){level=Math.max(50,Math.min(200,Math.round(v/10)*10));range.value=level;label.textContent=level+'%';document.querySelectorAll('.sheet table').forEach(function(t){t.style.zoom=level+'%'});}"+
      "document.getElementById('zoomout').onclick=function(){setZoom(level-10)};document.getElementById('zoomin').onclick=function(){setZoom(level+10)};document.getElementById('zoomreset').onclick=function(){setZoom(100)};range.oninput=function(){setZoom(Number(range.value))};"+
      "document.querySelectorAll('.tabs button').forEach(function(b){b.addEventListener('click',function(){document.querySelectorAll('.tabs button').forEach(function(x){x.classList.remove('active')});document.querySelectorAll('.sheet').forEach(function(x){x.classList.remove('active')});b.classList.add('active');document.getElementById('sheet'+b.dataset.sheet).classList.add('active')})});"+
      "var startDistance=0,startZoom=100;document.querySelectorAll('.scroll').forEach(function(el){el.addEventListener('touchstart',function(e){if(e.touches.length===2){var a=e.touches[0],b=e.touches[1];startDistance=Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY);startZoom=level;}},{passive:true});el.addEventListener('touchmove',function(e){if(e.touches.length===2&&startDistance>0){var a=e.touches[0],b=e.touches[1],distance=Math.hypot(a.clientX-b.clientX,a.clientY-b.clientY);setZoom(startZoom*distance/startDistance);e.preventDefault();}},{passive:false});el.addEventListener('touchend',function(e){if(e.touches.length<2)startDistance=0;},{passive:true});});setZoom(100);})();</script></body></html>");
    return html.toString();
  }
}
