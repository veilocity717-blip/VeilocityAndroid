package org.veilocity.arkhive;

import android.app.*;
import com.google.mlkit.nl.translate.*;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.android.gms.tasks.*;
import org.json.JSONObject;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.webkit.*;
import android.widget.*;
import android.provider.DocumentsContract;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
  static final int PICK=42;
  // Short-lived, bounded SAF directory cache: avoids repeated provider queries during page loads.
  static final long DIRECTORY_CACHE_MS=15000L;
  final Map<String,DirectorySnapshot> directoryCache=Collections.synchronizedMap(
      new LinkedHashMap<String,DirectorySnapshot>(64,0.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,DirectorySnapshot> eldest){return size()>64;}
      });
  static class DirectorySnapshot {
    final long created; final List<String> entries;
    DirectorySnapshot(long created,List<String> entries){this.created=created;this.entries=entries;}
  }
  void clearDirectoryCache(){directoryCache.clear();}
  void diagnostic(String event,Throwable error){
    android.util.Log.e("VeilocityStability",event,error);
  }

  // Private, persistent court-photo cache: never writes into the user's archive.
  static final long PHOTO_CACHE_LIMIT=300L*1024*1024;
  static final long PHOTO_FILE_LIMIT=24L*1024*1024;
  final Set<String> warming=Collections.synchronizedSet(new HashSet<String>());
  volatile int photoCacheGeneration=0;
  File photoCacheDir(){File dir=new File(getFilesDir(),"court-photo-cache");dir.mkdirs();return dir;}
  String photoCacheKey(String path){
    try{byte[] digest=java.security.MessageDigest.getInstance("SHA-256").digest((tree.toString()+"|"+path).getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}catch(Exception e){return Integer.toHexString(path.hashCode());}
  }
  boolean courtPhoto(String path){
    String p=path.replace('\\','/').toLowerCase(Locale.ROOT);
    if(!p.matches(".*\\.(png|jpe?g|webp|gif|bmp|avif)$"))return false;
    if(p.startsWith("1_library/")||p.startsWith("1_library\\"))return false;
    // Court attachments and images belonging to country/language archive folders.
    return p.contains("court") || (p.contains("/") && !p.startsWith("_"));
  }
  void trimPhotoCache(){File[] all=photoCacheDir().listFiles();if(all==null)return;Arrays.sort(all,Comparator.comparingLong(File::lastModified));long total=0;for(File f:all)if(f.isFile())total+=f.length();for(File f:all){if(total<=PHOTO_CACHE_LIMIT)break;if(f.isFile()){long size=f.length();if(f.delete())total-=size;}}}
  // Small, persistent previews are separate from original image cache and never touch the archive.
  static final long PREVIEW_CACHE_LIMIT=120L*1024*1024;
  static final int PREVIEW_MAX_EDGE=1600;
  final Set<String> previewWarming=Collections.synchronizedSet(new HashSet<String>());
  File previewDir(){File d=new File(getFilesDir(),"court-photo-previews");d.mkdirs();return d;}
  File previewFile(String path){return new File(previewDir(),photoCacheKey(path)+".jpg");}
  void trimPreviews(){
    File[] all=previewDir().listFiles();if(all==null)return;
    Arrays.sort(all,Comparator.comparingLong(File::lastModified));long total=0;
    for(File f:all)if(f.isFile())total+=f.length();
    for(File f:all){if(total<=PREVIEW_CACHE_LIMIT)break;if(f.isFile()){long n=f.length();if(f.delete())total-=n;}}
  }
  void cachePreview(String path,Uri uri,int generation){
    if(!courtPhoto(path)||generation!=photoCacheGeneration)return;
    File dst=previewFile(path);if(dst.isFile()&&dst.length()>0)return;
    String key=photoCacheKey(path);if(!previewWarming.add(key))return;
    File tmp=null;Bitmap bitmap=null;
    try{
      long size=documentSize(uri);if(size>PHOTO_FILE_LIMIT)return;
      BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
      try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)return;BitmapFactory.decodeStream(in,null,bounds);}
      if(bounds.outWidth<=0||bounds.outHeight<=0)return;
      int sample=1;
      while(Math.max(bounds.outWidth/sample,bounds.outHeight/sample)>PREVIEW_MAX_EDGE*2)sample*=2;
      BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
      try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)return;bitmap=BitmapFactory.decodeStream(in,null,options);}
      if(bitmap==null||generation!=photoCacheGeneration)return;
      int w=bitmap.getWidth(),h=bitmap.getHeight();float ratio=Math.min(1f,PREVIEW_MAX_EDGE/(float)Math.max(w,h));
      if(ratio<1f){Bitmap scaled=Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(w*ratio)),Math.max(1,Math.round(h*ratio)),true);if(scaled!=bitmap){bitmap.recycle();bitmap=scaled;}}
      // JPEG previews flatten transparency on a dark background; originals remain untouched.
      Bitmap flat=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.RGB_565);
      android.graphics.Canvas canvas=new android.graphics.Canvas(flat);canvas.drawColor(Color.rgb(8,10,13));canvas.drawBitmap(bitmap,0,0,null);
      tmp=new File(previewDir(),key+".tmp");
      try(FileOutputStream os=new FileOutputStream(tmp)){if(!flat.compress(Bitmap.CompressFormat.JPEG,82,os))return;}
      flat.recycle();
      if(generation==photoCacheGeneration&&tmp.length()>0&&tmp.renameTo(dst))trimPreviews();
    }catch(Exception e){diagnostic("Archive preview generation",e);}finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();if(tmp!=null)tmp.delete();previewWarming.remove(key);}
  }
  void cachePhoto(String path,Uri uri,int generation){
    if(!courtPhoto(path)||generation!=photoCacheGeneration)return;
    String key=photoCacheKey(path);File dst=new File(photoCacheDir(),key);
    if(dst.isFile()){dst.setLastModified(System.currentTimeMillis());return;}
    if(!warming.add(key))return;
    File temp=null;
    try{
      long size=documentSize(uri);if(size<=0||size>PHOTO_FILE_LIMIT)return;
      temp=new File(photoCacheDir(),key+".tmp");
      try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(temp)){
        if(in==null)return;byte[] buf=new byte[65536];int n;long copied=0;
        while((n=in.read(buf))!=-1){copied+=n;if(copied>PHOTO_FILE_LIMIT||generation!=photoCacheGeneration)return;out.write(buf,0,n);}
      }
      if(generation==photoCacheGeneration&&temp.length()==size){if(!temp.renameTo(dst))return;trimPhotoCache();}
    }catch(Exception e){diagnostic("Court photo cache",e);}finally{if(temp!=null)temp.delete();warming.remove(key);}
  }
  void warmCourtPhotosAsync(){
    final int generation=++photoCacheGeneration;
    new Thread(()->{
      try{
        // Scan Court and country archive folders, excluding Library and hidden folders.
        Uri root=resolve("");if(root==null)return;
        ArrayDeque<String> todo=new ArrayDeque<>();
        for(String name:names(root)){
          String lower=name.toLowerCase(Locale.ROOT);
          if(lower.startsWith("1_library")||lower.startsWith("."))continue;
          Uri folder=child(root,name);
          if(folder!=null&&isDirectory(folder))todo.add(name);
        }
        int inspected=0;
        while(!todo.isEmpty()&&generation==photoCacheGeneration&&inspected<8000){
          String path=todo.removeFirst();Uri dir=resolve(path);if(dir==null)continue;
          for(String name:names(dir)){
            if(generation!=photoCacheGeneration||++inspected>=8000)break;
            String childPath=path+"/"+name;Uri file=child(dir,name);if(file==null)continue;
            if(isDirectory(file)){todo.add(childPath);continue;}
            if(courtPhoto(childPath)){cachePreview(childPath,file,generation);cachePhoto(childPath,file,generation);}
          }
        }
      }catch(Exception e){diagnostic("Archive photo cache warmup",e);}
    },"VeilocityArchivePhotoWarmup").start();
  }
  Uri tree; WebView web; FrameLayout htmlViewport; LinearLayout htmlSearchPanel; LinearLayout root, toolbar; TextView title; Button toggleControls; boolean controlsVisible=true; ServerSocket socket; ExecutorService pool; int port;
  LinearLayout pdfPanel; FrameLayout pdfViewport; LinearLayout pdfFindPanel; LinearLayout pdfJumpPanel; ImageView pdfImage; View pdfHighlightOverlay; FrameLayout pdfPageLayer; TextView pdfPageLabel; PdfRenderer pdfRenderer; ParcelFileDescriptor pdfFd; File pdfTemp; int pdfPage=0; int pdfGeneration=0; int pdfSearchGeneration=0; boolean pdfVisible=false; float pdfZoom=1f; Bitmap pdfBitmap; String pdfDocumentKey=""; String pdfHighlightTerm=""; java.util.ArrayList<android.graphics.RectF> pdfHighlightRects=new java.util.ArrayList<>(); int pdfActiveHighlight=-1; Button pdfBookmarkButton; HorizontalScrollView pdfHorizontalScroll; ScrollView pdfVerticalScroll;
  @Override public void onCreate(Bundle b){ super.onCreate(b); buildUI(); String saved=getPreferences(0).getString("tree",null); if(saved!=null){tree=Uri.parse(saved);startServer();ensureNoMediaAsync(tree);warmCourtPhotosAsync();bootstrapArchiveFilesAsync();} else startServer(); }
  int dp(int value){return (int)(value*getResources().getDisplayMetrics().density+0.5f);}
  void buildUI(){
    root=new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(Color.rgb(13,17,25));
    setContentView(root);
    // Respect the system status/navigation bars, including edge-to-edge Android versions.
    final int defaultTop=dp(24);
    root.setPadding(dp(12),defaultTop,dp(12),dp(8));
    if(android.os.Build.VERSION.SDK_INT>=21){
      root.setOnApplyWindowInsetsListener((view,insets)->{
        int top=insets.getSystemWindowInsetTop();
        int bottom=insets.getSystemWindowInsetBottom();
        root.setPadding(dp(12),Math.max(top+dp(20),defaultTop),dp(12),Math.max(bottom,dp(8)));
        return insets;
      });
      root.requestApplyInsets();
    }
    // Centre the complete control group horizontally below the status bar.
    toolbar=new LinearLayout(this);
    toolbar.setOrientation(LinearLayout.HORIZONTAL);
    toolbar.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams barParams=new LinearLayout.LayoutParams(-1,dp(68));
    barParams.bottomMargin=dp(12);
    root.addView(toolbar,barParams);
    button("⌂",()->{closePdf();returnToHome();});
    button("←",()->navigateBackAndStopVideo());
    button("→",()->{if(pdfVisible)return;if(web.canGoForward())web.goForward();});
    button("⟳",()->{clearDirectoryCache();if(pdfVisible)renderPdfPage();else web.reload();});
    button("▣",()->{closePdf();chooseFolder();});
    button("⌕",()->{if(pdfVisible)showPdfSearch();else showHtmlSearch();});
    // Familiar up/down arrows jump to the beginning or end of the current document.
    button("⇧",()->scrollCurrentPage(false));
    button("⇩",()->scrollCurrentPage(true));
    // Keep the show/hide toggle accessible even when the navigation toolbar is collapsed.
    toggleControls=new Button(this);
    toggleControls.setAllCaps(false);
    toggleControls.setTextColor(Color.WHITE);
    toggleControls.setTextSize(14);
    roundNativeButton(toggleControls);
    LinearLayout.LayoutParams toggleParams=new LinearLayout.LayoutParams(-1,dp(42));
    toggleParams.bottomMargin=dp(5);
    root.addView(toggleControls,toggleParams);
    controlsVisible=getPreferences(0).getBoolean("controls_visible",true);
    applyControlsVisibility();
    toggleControls.setOnClickListener(v->{
      controlsVisible=!controlsVisible;
      getPreferences(0).edit().putBoolean("controls_visible",controlsVisible).apply();
      applyControlsVisibility();
    });
    title=new TextView(this);
    title.setText("ARKHIVE");
    title.setTextColor(Color.WHITE);
    title.setTextSize(15);
    title.setGravity(Gravity.CENTER);
    root.addView(title,new LinearLayout.LayoutParams(-1,dp(30)));
    htmlViewport=new FrameLayout(this);
    root.addView(htmlViewport,new LinearLayout.LayoutParams(-1,0,1));
    web=new WebView(this);
    htmlViewport.addView(web,new FrameLayout.LayoutParams(-1,-1));
    web.setBackgroundColor(Color.rgb(13,17,25));
    web.getSettings().setJavaScriptEnabled(true);
    web.getSettings().setDomStorageEnabled(true);
    // Allow WebView to reuse the landing page and its resources on history navigation.
    web.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_DEFAULT);
    web.getSettings().setAllowFileAccess(false);
    web.getSettings().setAllowContentAccess(false);
    web.addJavascriptInterface(new CourtTranslationBridge(),"VeilocityCourtTranslate");
    web.setWebViewClient(new WebViewClient(){@Override public void onPageCommitVisible(WebView v,String url){super.onPageCommitVisible(v,url);if(isCourtTranslationPage(url))v.evaluateJavascript(COURT_TRANSLATION_UI,null);}@Override public void onPageFinished(WebView v,String url){super.onPageFinished(v,url);if(isCourtTranslationPage(url))v.evaluateJavascript(COURT_TRANSLATION_UI,null);if(url!=null&&url.toLowerCase(Locale.ROOT).matches(".*/index[.]html(?:[?#].*)?$")){v.evaluateJavascript("",null);}}@Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String u=r.getUrl().toString();if(u.equals("veilocity://choose-folder")){chooseFolder();return true;}if(u.startsWith("mailto:")){openContactEmail();return true;}if(isCourtTranslationPage(v.getUrl())&&(u.contains("translate.google.com/")||u.contains("google.com/translate"))){v.evaluateJavascript(COURT_TRANSLATION_UI+";window.__veilocityCourtOpenTranslation&&window.__veilocityCourtOpenTranslation();",null);return true;}if(isLocalPdf(u)){openPdf(u);return true;}if(r.isForMainFrame()&&isLocalArchiveImage(u)){v.evaluateJavascript("window.__veilocityOpenImage&&window.__veilocityOpenImage("+org.json.JSONObject.quote(u)+")",null);return true;}if(isCourtArchiveNavigation(u)){v.loadUrl("http://127.0.0.1:"+port+"/files/court-of-illusions-trafficking/ENGLISH%20-%20Court.html");return true;}if(u.startsWith("http://127.0.0.1:"))return false; if(u.startsWith("http://")||u.startsWith("https://")){Uri destination=redirectLegacyGithubRepo(r.getUrl());startActivity(new Intent(Intent.ACTION_VIEW,destination));return true;}return true;}});
    buildPdfPanel();
  }
  // Translation bridge is deliberately limited to the exact local Court page.
  // Do not expose it to arbitrary external pages or other archive documents.
  final Map<String,Translator> courtTranslators=new HashMap<>();
  boolean isCourtTranslationPage(String url){
    if(url==null)return false;
    try{
      Uri u=Uri.parse(url);
      return "http".equals(u.getScheme()) && "127.0.0.1".equals(u.getHost())
          && u.getPort()==port && "/files/court-of-illusions-trafficking/ENGLISH - Court.html".equalsIgnoreCase(Uri.decode(u.getPath()));
    }catch(Exception e){return false;}
  }
  void courtJs(String js){runOnUiThread(()->{if(web!=null&&isCourtTranslationPage(web.getUrl()))web.evaluateJavascript(js,null);});}
  class CourtTranslationBridge {
    @JavascriptInterface public String getSaved(String key){
      if(!key.startsWith("veilocity.court."))return null;
      try{File f=new File(getFilesDir(),key.replaceAll("[^a-zA-Z0-9._-]","_")+".json");if(!f.isFile()||f.length()>8*1024*1024)return null;
        byte[] data=new byte[(int)f.length()];try(FileInputStream in=new FileInputStream(f)){int pos=0,n;while(pos<data.length&&(n=in.read(data,pos,data.length-pos))>0)pos+=n;if(pos!=data.length)return null;}return new String(data,StandardCharsets.UTF_8);
      }catch(Exception e){return null;}
    }
    @JavascriptInterface public void setSaved(String key,String value){
      if(!key.startsWith("veilocity.court.")||value==null||value.length()>7000000)return;
      try{File f=new File(getFilesDir(),key.replaceAll("[^a-zA-Z0-9._-]","_")+".json");File temp=new File(f.getPath()+".tmp");try(FileOutputStream out=new FileOutputStream(temp)){out.write(value.getBytes(StandardCharsets.UTF_8));out.getFD().sync();}if(!temp.renameTo(f)){f.delete();temp.renameTo(f);}}catch(Exception e){diagnostic("Court translation save",e);}
    }
    @JavascriptInterface public void removeSaved(String key){if(key.startsWith("veilocity.court."))new File(getFilesDir(),key.replaceAll("[^a-zA-Z0-9._-]","_")+".json").delete();}

    @JavascriptInterface public void translate(String requestId,String language,String text){
      if(requestId==null||!requestId.matches("[0-9]{1,6}")||text==null||text.length()>4000)return;
      if(!Arrays.asList("en","fr","es","de","pl","ja","ar","it","pt","nl","ru","uk","hi","zh","tr","sv","vi","ko","id","th").contains(language))return;
      runOnUiThread(()->{
        if(!isCourtTranslationPage(web.getUrl()))return;
        if("en".equals(language)){courtResult(requestId,text,null);return;}
        Translator translator=courtTranslators.get(language);
        if(translator==null){
          translator=Translation.getClient(new TranslatorOptions.Builder()
              .setSourceLanguage(TranslateLanguage.ENGLISH).setTargetLanguage(language).build());
          courtTranslators.put(language,translator);
        }
        final Translator active=translator;
        active.downloadModelIfNeeded(new DownloadConditions.Builder().build())
          .addOnSuccessListener(unused->active.translate(text)
            .addOnSuccessListener(result->courtResult(requestId,result,null))
            .addOnFailureListener(err->courtResult(requestId,null,err.getMessage())))
          .addOnFailureListener(err->courtResult(requestId,null,"Language model unavailable: "+err.getMessage()));
      });
    }
  }
  void courtResult(String id,String result,String error){
    String js="window.__courtTranslationResult&&window.__courtTranslationResult("+id+","+
      (result==null?"null":JSONObject.quote(result))+","+(error==null?"null":JSONObject.quote(error))+")";
    courtJs(js);
  }
  static final String COURT_TRANSLATION_UI =
      "    (function(){\n" +
      "      if(window.__veilocityCourtTranslateInstalled){return;}\n" +
      "      window.__veilocityCourtTranslateInstalled=true;\n" +
      "      var panel=document.createElement('div');panel.id='veilocity-court-translation';\n" +
      "      panel.style.cssText='display:none;position:fixed;right:12px;bottom:16px;z-index:2147483646;background:#263642;color:#f5f6f6;border:1px solid #688b8c;border-radius:13px;padding:9px;box-shadow:0 5px 18px #0006;font:14px system-ui;max-width:calc(100vw - 24px)';\n" +
      "      panel.innerHTML='<div style=\"display:flex;align-items:center;gap:7px;flex-wrap:wrap\"><label for=\"vct-lang\">🌐 Court translation</label><select id=\"vct-lang\" style=\"background:#17262e;color:white;border:1px solid #759293;border-radius:7px;padding:6px\"><option value=\"en\">Original English</option><option value=\"fr\">French</option><option value=\"es\">Spanish</option><option value=\"de\">German</option><option value=\"pl\">Polish</option><option value=\"ja\">Japanese</option><option value=\"ar\">Arabic</option><option value=\"it\">Italian</option><option value=\"pt\">Portuguese</option><option value=\"nl\">Dutch</option><option value=\"ru\">Russian</option><option value=\"uk\">Ukrainian</option><option value=\"hi\">Hindi</option><option value=\"zh\">Chinese</option><option value=\"tr\">Turkish</option><option value=\"sv\">Swedish</option><option value=\"vi\">Vietnamese</option><option value=\"ko\">Korean</option><option value=\"id\">Indonesian</option><option value=\"th\">Thai</option></select><button id=\"vct-go\" style=\"border:0;border-radius:7px;background:#398581;color:white;padding:7px\">Translate</button></div><small id=\"vct-status\" style=\"display:block;margin-top:4px;opacity:.85\">First use downloads a language model.</small>';\n" +
      "      document.body.appendChild(panel);panel.insertAdjacentHTML('beforeend','<button id=\"vct-close\" style=\"margin-top:5px;border:0;border-radius:7px;padding:5px;background:#485764;color:white\">Close</button>');panel.querySelector('#vct-close').onclick=function(){panel.style.display='none';};\n" +
      "      window.__veilocityCourtOpenTranslation=function(){panel.style.display='block';panel.querySelector('#vct-lang').focus();};\n" +
      "      var select=panel.querySelector('#vct-lang'),button=panel.querySelector('#vct-go'),status=panel.querySelector('#vct-status');\n" +
      "      var nodes=[],original=[],run=0,pending=new Map(),nextId=1;var CACHE_KEY='veilocity.court.translation.v1',PREF_KEY='veilocity.court.language.v1';\n" +
      "      var walker=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){\n" +
      "        if(!n.nodeValue.trim()||!n.parentElement)return NodeFilter.FILTER_REJECT;\n" +
      "        if(n.parentElement.closest('#veilocity-court-translation,script,style,noscript,textarea,code,pre,svg'))return NodeFilter.FILTER_REJECT;\n" +
      "        return NodeFilter.FILTER_ACCEPT;\n" +
      "      }});var n;while((n=walker.nextNode())&&nodes.length<3000){nodes.push(n);original.push(n.nodeValue);}\n" +
      "      var signature=(function(){var h=2166136261;original.forEach(function(t){for(var j=0;j<t.length;j++){h^=t.charCodeAt(j);h=Math.imul(h,16777619);}});return String(h>>>0)+':'+original.length;})();\n" +
      "function cached(){try{var x=JSON.parse(VeilocityCourtTranslate.getSaved(CACHE_KEY)||'null');return x&&x.signature===signature?x:null;}catch(e){return null;}}\n" +
      "function save(lang){try{VeilocityCourtTranslate.setSaved(PREF_KEY,lang);}catch(e){}}\n" +
      "function applyCache(lang){var x=cached();if(!x||x.lang!==lang||!Array.isArray(x.values)||x.values.length!==nodes.length)return false;run++;pending.clear();nodes.forEach(function(n,i){if(n.isConnected)n.nodeValue=x.values[i];});select.value=lang;status.textContent='Saved '+lang+' translation restored offline.';button.disabled=false;return true;}\n" +
      "function restore(){run++;pending.clear();nodes.forEach(function(n,i){if(n.isConnected)n.nodeValue=original[i]});status.textContent='Original English restored.';button.disabled=false;}\n" +
      "      panel.insertAdjacentHTML('beforeend','<button id=\"vct-clear\" style=\"margin:5px;border:0;border-radius:7px;padding:5px;background:#485764;color:white\">Clear saved translation</button>');\n" +
      "panel.querySelector('#vct-clear').onclick=function(){try{VeilocityCourtTranslate.removeSaved(CACHE_KEY);VeilocityCourtTranslate.removeSaved(PREF_KEY);}catch(e){}select.value='en';restore();status.textContent='Saved translation cleared.';};\n" +
      "window.__courtTranslationResult=function(id,result,error){var job=pending.get(id);if(!job)return;pending.delete(id);job(result,error);};\n" +
      "      button.addEventListener('click',function(){\n" +
      "        restore();var lang=select.value;save(lang);if(lang==='en')return;if(applyCache(lang))return;\n" +
      "        var current=++run,index=0,done=0,fail=0,active=0,values=original.slice();\n" +
      "        button.disabled=true;status.textContent='Translating 0 / '+nodes.length+' text segments…';\n" +
      "        function pump(){\n" +
      "          if(current!==run)return;\n" +
      "          while(active<3&&index<nodes.length){\n" +
      "            (function(i){var id=nextId++;active++;\n" +
      "              pending.set(id,function(result,error){\n" +
      "                if(current!==run)return;\n" +
      "                active--;done++;if(error)fail++;else if(result!=null&&nodes[i].isConnected){nodes[i].nodeValue=result;values[i]=result;}\n" +
      "                if(done%10===0||done===nodes.length)status.textContent='Translated '+done+' / '+nodes.length+(fail?' ('+fail+' unavailable)':'');\n" +
      "                if(done===nodes.length){button.disabled=false;if(!fail){try{VeilocityCourtTranslate.setSaved(CACHE_KEY,JSON.stringify({signature:signature,lang:lang,values:values}));status.textContent+=' — saved offline';}catch(e){status.textContent+=' — cache storage full';}}else status.textContent+=' — incomplete (not cached)';}else pump();\n" +
      "              });\n" +
      "              try{VeilocityCourtTranslate.translate(String(id),lang,original[i].trim());}\n" +
      "              catch(e){var callback=pending.get(id);pending.delete(id);callback(null,String(e));}\n" +
      "            })(index++);\n" +
      "          }\n" +
      "        }\n" +
      "        if(!nodes.length){button.disabled=false;status.textContent='No text found.';}else pump();\n" +
      "      });\n" +
      "      select.addEventListener('change',function(){if(select.value==='en')restore();});\n" +
      "try{var preferred=VeilocityCourtTranslate.getSaved(PREF_KEY)||'en';if(preferred!=='en'){select.value=preferred;if(!applyCache(preferred))status.textContent='Preferred language: '+preferred+'. Tap Translate to download or refresh.';}}catch(e){}\n" +
      "      document.querySelectorAll('#veilocity-court-translate a[href]').forEach(function(a){a.removeAttribute('target');a.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();window.__veilocityCourtOpenTranslation();},true);});\n" +
      "      // Keep legacy Google Translate links within the Court page from navigating away.\n" +
      "      document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[href]');if(!a)return;\n" +
      "        if(a.href.indexOf('translate.google')!==-1||a.href.indexOf('google.com/translate')!==-1){e.preventDefault();e.stopPropagation();window.__veilocityCourtOpenTranslation();}\n" +
      "      },true);\n" +
      "    })();\n" +
      "    ";

  // Use the device's configured email application; do not send mail automatically.
  void openContactEmail(){
    Intent email=new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:stephencooke.c@gmail.com"));
    try{startActivity(email);}catch(android.content.ActivityNotFoundException e){
      new AlertDialog.Builder(this).setTitle("No email application")
        .setMessage("Install or configure an email application to contact stephencooke.c@gmail.com")
        .setPositiveButton("OK",null).show();
    }
  }
  // Redirect legacy GitHub repository buttons in externally supplied archive HTML.
  // Match the repository path exactly, preserving any trailing path/query/fragment.
  Uri redirectLegacyGithubRepo(Uri original){
    String host=original.getHost();
    String path=original.getEncodedPath();
    if(host!=null && host.equalsIgnoreCase("github.com") && path!=null){
      String oldRepo="/veilocity717-blip/Veilocity";
      if(path.equalsIgnoreCase(oldRepo) || path.toLowerCase(java.util.Locale.ROOT).startsWith((oldRepo+"/").toLowerCase(java.util.Locale.ROOT))){
        return original.buildUpon().encodedPath("/veilocity717-blip/VeilocityAndroid"+path.substring(oldRepo.length())).build();
      }
    }
    return original;
  }
  // Scroll within the active viewer, without navigating away from the document.
  void scrollCurrentPage(boolean bottom){
    if(pdfVisible){
      if(pdfVerticalScroll!=null){
        pdfVerticalScroll.post(()->pdfVerticalScroll.smoothScrollTo(0,bottom ? Math.max(0,pdfVerticalScroll.getChildAt(0).getHeight()-pdfVerticalScroll.getHeight()) : 0));
      }
      return;
    }
    if(web!=null){
      String destination=bottom ? "Math.max(document.body.scrollHeight,document.documentElement.scrollHeight)" : "0";
      web.evaluateJavascript("(function(){var y="+destination+";window.scrollTo({top:y,behavior:'smooth'});})()",null);
    }
  }
  // Non-modal, draggable HTML find panel. It leaves the underlying WebView scrollable.
  // Only the HTML search is changed; PDF search and archive viewers remain untouched.
  void hideHtmlSearch(){
    if(htmlSearchPanel!=null){
      htmlViewport.removeView(htmlSearchPanel);
      htmlSearchPanel=null;
    }
    if(web!=null){web.clearMatches();web.setFindListener(null);}
  }
  void showHtmlSearch(){
    if(web==null||htmlViewport==null)return;
    if(htmlSearchPanel!=null){htmlSearchPanel.bringToFront();return;}
    final int panelWidth=Math.min(dp(320),getResources().getDisplayMetrics().widthPixels-dp(36));
    final LinearLayout panel=new LinearLayout(this);
    htmlSearchPanel=panel;
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(12),dp(8),dp(12),dp(10));
    GradientDrawable bg=new GradientDrawable();
    bg.setColor(Color.rgb(45,48,55));bg.setCornerRadius(dp(12));
    bg.setStroke(dp(1),Color.rgb(91,99,105));
    panel.setBackground(bg);
    panel.setElevation(dp(12));

    final LinearLayout header=new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    final TextView heading=new TextView(this);
    heading.setText("☰  Find in page  ·  Drag to move");
    heading.setTextColor(Color.WHITE);
    heading.setTextSize(13);
    heading.setPadding(dp(4),dp(6),0,dp(6));
    header.addView(heading,new LinearLayout.LayoutParams(0,dp(36),1));
    final Button close=new Button(this);
    close.setText("×");close.setAllCaps(false);
    close.setTextColor(Color.WHITE);close.setTextSize(20);
    close.setPadding(0,0,0,0);close.setMinWidth(0);close.setMinimumWidth(0);
    close.setBackgroundColor(Color.TRANSPARENT);
    header.addView(close,new LinearLayout.LayoutParams(dp(40),dp(38)));
    panel.addView(header);

    final EditText query=new EditText(this);
    query.setSingleLine(true);
    query.setTextSize(15);
    query.setTextColor(Color.WHITE);
    query.setHintTextColor(Color.LTGRAY);
    query.setHint("Search this page");
    query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    panel.addView(query,new LinearLayout.LayoutParams(-1,dp(48)));

    final TextView matchCount=new TextView(this);
    matchCount.setTextColor(Color.rgb(180,225,215));
    matchCount.setTextSize(12);
    matchCount.setPadding(dp(4),dp(2),dp(4),dp(3));
    panel.addView(matchCount);

    final LinearLayout actions=new LinearLayout(this);
    actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    Button previous=new Button(this),next=new Button(this);
    previous.setText("Previous");next.setText("Next");
    previous.setAllCaps(false);next.setAllCaps(false);
    previous.setTextSize(12);next.setTextSize(12);
    actions.addView(previous,new LinearLayout.LayoutParams(0,dp(43),1));
    actions.addView(next,new LinearLayout.LayoutParams(0,dp(43),1));
    panel.addView(actions);

    FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(panelWidth,-2,Gravity.TOP|Gravity.START);
    position.leftMargin=dp(8);position.topMargin=dp(8);
    htmlViewport.addView(panel,position);
    close.setOnClickListener(v->hideHtmlSearch());
    previous.setOnClickListener(v->{if(query.length()>0)web.findNext(false);});
    next.setOnClickListener(v->{if(query.length()>0)web.findNext(true);});
    web.setFindListener((active,total,done)->{
      if(done&&htmlSearchPanel==panel)matchCount.setText(total==0?"No matches":((active+1)+" of "+total+" matches"));
    });
    query.addTextChangedListener(new android.text.TextWatcher(){
      public void beforeTextChanged(CharSequence t,int st,int count,int after){}
      public void onTextChanged(CharSequence t,int st,int before,int count){
        if(htmlSearchPanel!=panel)return;
        String term=t.toString();
        if(term.isEmpty()){web.clearMatches();matchCount.setText("");}
        else web.findAllAsync(term);
      }
      public void afterTextChanged(android.text.Editable e){}
    });
    // Drag only by the heading, so the text field and buttons remain usable.
    heading.setOnTouchListener(new View.OnTouchListener(){
      float downX,downY;int initialX,initialY;
      @Override public boolean onTouch(View v,android.view.MotionEvent event){
        switch(event.getActionMasked()){
          case android.view.MotionEvent.ACTION_DOWN:
            downX=event.getRawX();downY=event.getRawY();
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)panel.getLayoutParams();
            initialX=lp.leftMargin;initialY=lp.topMargin;return true;
          case android.view.MotionEvent.ACTION_MOVE:
            FrameLayout.LayoutParams move=(FrameLayout.LayoutParams)panel.getLayoutParams();
            int maxX=Math.max(0,htmlViewport.getWidth()-panel.getWidth());
            int maxY=Math.max(0,htmlViewport.getHeight()-panel.getHeight());
            move.leftMargin=Math.max(0,Math.min(maxX,initialX+Math.round(event.getRawX()-downX)));
            move.topMargin=Math.max(0,Math.min(maxY,initialY+Math.round(event.getRawY()-downY)));
            panel.setLayoutParams(move);return true;
          case android.view.MotionEvent.ACTION_UP:
          case android.view.MotionEvent.ACTION_CANCEL:return true;
        }
        return false;
      }
    });
    query.requestFocus();
    query.post(()->((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
      .showSoftInput(query,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
  }
  boolean isCourtArchiveNavigation(String url){
    try{
      Uri uri=Uri.parse(url);
      if(!"127.0.0.1".equals(uri.getHost())||uri.getPort()!=port)return false;
      String path=uri.getPath();
      if(path==null||!path.startsWith("/files/"))return false;
      String lower=path.toLowerCase(Locale.ROOT);
      // Only intercept Court-specific HTML links, not their images or other assets.
      if(!lower.endsWith(".html")&&!lower.endsWith(".htm"))return false;
      if(lower.equals("/files/_image_view.html"))return false;
      if(lower.equals("/files/court-of-illusions-trafficking/english - court.html"))return false;
      return lower.contains("court-of-illusions-trafficking/") ||
             lower.contains("court of illusions") || lower.contains("court-of-illusions") ||
             lower.contains("english - court.html");
    }catch(Exception ignored){return false;}
  }
  // Android WebView cannot reliably display PDF documents. Render local PDFs
  // with the platform PdfRenderer so the viewer works entirely offline.
  // Catch raw-image navigation at the WebView level, independently of HTML click handlers.
  boolean isLocalArchiveImage(String url){
    try{
      Uri u=Uri.parse(url);
      if(!"http".equalsIgnoreCase(u.getScheme())||!"127.0.0.1".equals(u.getHost())||u.getPort()!=port)return false;
      String path=u.getPath();
      if(path==null||!path.startsWith("/files/"))return false;
      String relative=path.substring("/files/".length());
      if(relative.startsWith("1_Library/")||relative.startsWith("_")||relative.contains(".."))return false;
      return relative.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp|avif)$");
    }catch(Exception ignored){return false;}
  }
  boolean isLocalPdf(String url){
    try { Uri u=Uri.parse(url);return "127.0.0.1".equals(u.getHost())&&u.getPort()==port&&u.getPath()!=null&&u.getPath().startsWith("/files/")&&u.getPath().toLowerCase(Locale.ROOT).endsWith(".pdf"); }
    catch(Exception e){return false;}
  }
  void buildPdfPanel(){
    pdfPanel=new LinearLayout(this);pdfPanel.setOrientation(LinearLayout.VERTICAL);
    pdfPanel.setBackgroundColor(Color.rgb(24,28,35));
    LinearLayout nav=new LinearLayout(this);nav.setGravity(Gravity.CENTER_VERTICAL);
    Button previous=new Button(this);roundNativeButton(previous);previous.setText("◀");nav.addView(previous,new LinearLayout.LayoutParams(0,dp(48),1));
    previous.setOnClickListener(v->{if(pdfRenderer!=null&&pdfPage>0){pdfPage--;renderPdfPage();}});
    pdfPageLabel=new TextView(this);pdfPageLabel.setTextColor(Color.WHITE);pdfPageLabel.setGravity(Gravity.CENTER);pdfPageLabel.setText("PDF");pdfPageLabel.setTextSize(14);
    nav.addView(pdfPageLabel,new LinearLayout.LayoutParams(0,dp(48),3));
    pdfPageLabel.setOnClickListener(v->showPdfPageJump());
    Button next=new Button(this);roundNativeButton(next);next.setText("▶");nav.addView(next,new LinearLayout.LayoutParams(0,dp(48),1));
    next.setOnClickListener(v->{if(pdfRenderer!=null&&pdfPage<pdfRenderer.getPageCount()-1){pdfPage++;renderPdfPage();}});
    Button close=new Button(this);roundNativeButton(close);close.setText("✕");nav.addView(close,new LinearLayout.LayoutParams(0,dp(48),1));close.setOnClickListener(v->closePdf());
    pdfPanel.addView(nav,new LinearLayout.LayoutParams(-1,dp(52)));
    LinearLayout tools=new LinearLayout(this);tools.setGravity(Gravity.CENTER_VERTICAL);
    Button zoomOut=new Button(this);roundNativeButton(zoomOut);zoomOut.setText("−");tools.addView(zoomOut,new LinearLayout.LayoutParams(0,dp(48),1));
    zoomOut.setOnClickListener(v->{if(pdfRenderer!=null){pdfZoom=Math.max(0.5f,pdfZoom/1.25f);renderPdfPage();}});
    Button zoomIn=new Button(this);roundNativeButton(zoomIn);zoomIn.setText("🔍 +");zoomIn.setTextSize(12);tools.addView(zoomIn,new LinearLayout.LayoutParams(0,dp(48),2));
    zoomIn.setOnClickListener(v->{if(pdfRenderer!=null){pdfZoom=Math.min(8f,pdfZoom*1.25f);renderPdfPage();}});
    Button reset=new Button(this);roundNativeButton(reset);reset.setText("Fit");tools.addView(reset,new LinearLayout.LayoutParams(0,dp(48),1));
    reset.setOnClickListener(v->{pdfZoom=1f;renderPdfPage();});
    pdfBookmarkButton=new Button(this);roundNativeButton(pdfBookmarkButton);pdfBookmarkButton.setText("☆ Save page");pdfBookmarkButton.setTextSize(12);
    tools.addView(pdfBookmarkButton,new LinearLayout.LayoutParams(0,dp(48),3));
    pdfBookmarkButton.setOnClickListener(v->togglePdfBookmark());
    pdfBookmarkButton.setOnLongClickListener(v->{showPdfBookmarks();return true;});
    Button goSaved=new Button(this);roundNativeButton(goSaved);goSaved.setText("Bookmarks");goSaved.setTextSize(12);
    tools.addView(goSaved,new LinearLayout.LayoutParams(0,dp(48),2));
    goSaved.setOnClickListener(v->showPdfBookmarks());
    // Compact PDF search control: shares the zoom/bookmark toolbar row.
    Button pdfSearch=new Button(this);roundNativeButton(pdfSearch);pdfSearch.setText("⌕");pdfSearch.setTextSize(23);
    pdfSearch.setAllCaps(false);pdfSearch.setContentDescription("Search PDF text");
    pdfSearch.setOnClickListener(v->showPdfSearch());
    tools.addView(pdfSearch,new LinearLayout.LayoutParams(0,dp(48),1));
    pdfPanel.addView(tools,new LinearLayout.LayoutParams(-1,dp(52)));
    pdfVerticalScroll=new ScrollView(this);pdfVerticalScroll.setFillViewport(true);
    pdfHorizontalScroll=new HorizontalScrollView(this);pdfHorizontalScroll.setFillViewport(true);
    pdfImage=new ImageView(this);pdfImage.setAdjustViewBounds(false);pdfImage.setScaleType(ImageView.ScaleType.FIT_XY);
    // The PDF and its highlight overlay share the same page-sized container.
    // Scrolling and zooming therefore move both layers in perfect sync.
    pdfPageLayer=new FrameLayout(this);
    pdfPageLayer.addView(pdfImage,new FrameLayout.LayoutParams(-1,-1));
    pdfHighlightOverlay=new View(this){
      final android.graphics.Paint paint=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
      @Override protected void onDraw(android.graphics.Canvas canvas){
        super.onDraw(canvas);
        if(pdfHighlightRects==null||pdfHighlightRects.isEmpty())return;
        for(int i=0;i<pdfHighlightRects.size();i++){
          paint.setColor(i==pdfActiveHighlight?0x99FF9900:0x88FFE14D);
          canvas.drawRoundRect(pdfHighlightRects.get(i),dp(2),dp(2),paint);
        }
      }
    };
    pdfPageLayer.addView(pdfHighlightOverlay,new FrameLayout.LayoutParams(-1,-1));
    pdfHorizontalScroll.addView(pdfPageLayer,new android.widget.FrameLayout.LayoutParams(-2,-2));
    pdfVerticalScroll.addView(pdfHorizontalScroll,new ScrollView.LayoutParams(-1,-2));
    pdfViewport=new FrameLayout(this);
    pdfViewport.addView(pdfVerticalScroll,new FrameLayout.LayoutParams(-1,-1));
    pdfPanel.addView(pdfViewport,new LinearLayout.LayoutParams(-1,0,1));
    root.addView(pdfPanel,new LinearLayout.LayoutParams(-1,0,1));pdfPanel.setVisibility(View.GONE);
  }
  String pdfBookmarkPreference(){return "pdf_bookmarks_"+Integer.toHexString(pdfDocumentKey.hashCode());}
  // Bookmarks are stored per document as a comma-separated list of zero-based pages.
  TreeSet<Integer> savedPdfPages(){
    TreeSet<Integer> pages=new TreeSet<>();
    String raw=getPreferences(0).getString(pdfBookmarkPreference(),"");
    for(String token:raw.split(",")){
      try{int page=Integer.parseInt(token.trim());if(page>=0)pages.add(page);}catch(NumberFormatException ignored){}
    }
    // Import the single-page bookmark from previous releases, if present.
    int old=getPreferences(0).getInt("pdf_bookmark_"+Integer.toHexString(pdfDocumentKey.hashCode()),-1);
    if(old>=0)pages.add(old);
    return pages;
  }
  void storePdfBookmarks(TreeSet<Integer> pages){
    StringBuilder result=new StringBuilder();
    for(int page:pages){if(result.length()>0)result.append(',');result.append(page);}
    getPreferences(0).edit().putString(pdfBookmarkPreference(),result.toString())
      .remove("pdf_bookmark_"+Integer.toHexString(pdfDocumentKey.hashCode())).apply();
  }
  void updatePdfBookmarkButton(){
    if(pdfBookmarkButton!=null)pdfBookmarkButton.setText(savedPdfPages().contains(pdfPage)?"★ Saved":"☆ Save page");
  }
  void togglePdfBookmark(){
    if(pdfRenderer==null)return;
    TreeSet<Integer> pages=savedPdfPages();
    if(pages.remove(pdfPage))Toast.makeText(this,"Page "+(pdfPage+1)+" bookmark removed",Toast.LENGTH_SHORT).show();
    else{pages.add(pdfPage);Toast.makeText(this,"Page "+(pdfPage+1)+" bookmarked",Toast.LENGTH_SHORT).show();}
    storePdfBookmarks(pages);updatePdfBookmarkButton();
  }
  // Text extraction is supported by newer Android PDF renderers. Older Android
  // versions and image-only/scanned pages retain the page-number-only label.
  String firstPdfLine(int pageNumber){
    if(pdfRenderer==null)return "";
    try(PdfRenderer.Page page=pdfRenderer.openPage(pageNumber)){
      java.lang.reflect.Method method=page.getClass().getMethod("getTextContents");
      Object contents=method.invoke(page);
      if(!(contents instanceof java.util.List))return "";
      for(Object item:(java.util.List<?>)contents){
        if(item==null)continue;
        Object value=item.getClass().getMethod("getText").invoke(item);
        if(!(value instanceof String))continue;
        String raw=(String)value;
        for(String line:raw.split("[\\r\\n]+")){
          String clean=line.replaceAll("\\s+"," ").trim();
          if(!clean.isEmpty()){
            int end=clean.offsetByCodePoints(0,Math.min(25,clean.codePointCount(0,clean.length())));
            return clean.substring(0,end);
          }
        }
      }
    }catch(Exception ignored){/* Platform does not expose extractable PDF text. */}
    return "";
  }
  String pdfBookmarkLabel(int page){
    String line=firstPdfLine(page);
    return "Page "+(page+1)+(line.isEmpty()?"":" — "+line);
  }
  void showPdfBookmarks(){
    if(pdfRenderer==null)return;
    TreeSet<Integer> pages=savedPdfPages();
    pages.removeIf(page->page>=pdfRenderer.getPageCount());
    if(pages.isEmpty()){
      new AlertDialog.Builder(this).setTitle("Bookmarks")
        .setMessage("No bookmarks saved for this PDF. Open a page and tap Save page.")
        .setPositiveButton("OK",null).show();return;
    }
    Integer[] numbers=pages.toArray(new Integer[0]);
    String[] labels=new String[numbers.length];
    for(int i=0;i<numbers.length;i++)labels[i]=pdfBookmarkLabel(numbers[i]);
    new AlertDialog.Builder(this).setTitle("Bookmarks — tap to open")
      .setItems(labels,(dialog,which)->{pdfPage=numbers[which];renderPdfPage();})
      .setNeutralButton("Remove bookmarks",(dialog,which)->showRemovePdfBookmarkDialog())
      .setNegativeButton("Close",null).show();
  }
  void showRemovePdfBookmarkDialog(){
    TreeSet<Integer> pages=savedPdfPages();
    if(pages.isEmpty())return;
    Integer[] numbers=pages.toArray(new Integer[0]);
    String[] labels=new String[numbers.length];
    for(int i=0;i<numbers.length;i++)labels[i]=pdfBookmarkLabel(numbers[i]);
    new AlertDialog.Builder(this).setTitle("Remove a bookmark")
      .setItems(labels,(dialog,which)->{pages.remove(numbers[which]);storePdfBookmarks(pages);updatePdfBookmarkButton();})
      .setNegativeButton("Cancel",null).show();
  }
  // Android 15 (API 35) exposes selectable PDF text through PdfRenderer.Page.
  // Earlier versions can still display PDFs but cannot search text with PdfRenderer.
  String extractPdfPageText(int pageNumber){
    if(pdfRenderer==null)return "";
    try(PdfRenderer.Page page=pdfRenderer.openPage(pageNumber)){
      java.lang.reflect.Method method=page.getClass().getMethod("getTextContents");
      Object contents=method.invoke(page);
      if(!(contents instanceof java.util.List))return "";
      StringBuilder result=new StringBuilder();
      for(Object item:(java.util.List<?>)contents){
        if(item==null)continue;
        Object text=item.getClass().getMethod("getText").invoke(item);
        if(text instanceof String)result.append((String)text).append(' ');
      }
      return result.toString();
    }catch(Exception ignored){return "";}
  }
  // PDF search shares the HTML find panel's non-modal, draggable presentation.
  // The existing PdfRenderer text extraction is retained (Android 15+).
  void hidePdfSearch(){
    ++pdfSearchGeneration; // Cancel any in-progress search safely.
    if(pdfFindPanel!=null&&pdfViewport!=null)pdfViewport.removeView(pdfFindPanel);
    pdfFindPanel=null;
    if(!pdfHighlightTerm.isEmpty()){
      pdfHighlightTerm="";pdfHighlightRects.clear();pdfActiveHighlight=-1;
      if(pdfHighlightOverlay!=null)pdfHighlightOverlay.invalidate();
    }
  }
  void showPdfSearch(){
    if(pdfRenderer==null||pdfViewport==null)return;
    if(pdfFindPanel!=null){pdfFindPanel.bringToFront();return;}
    if(Build.VERSION.SDK_INT<35){
      new AlertDialog.Builder(this).setTitle("PDF text search")
        .setMessage("PDF text search requires Android 15 or later with extractable PDF text. Scanned or image-only pages are not searchable.")
        .setPositiveButton("OK",null).show();return;
    }
    final int panelWidth=Math.min(dp(320),getResources().getDisplayMetrics().widthPixels-dp(36));
    final LinearLayout panel=new LinearLayout(this);
    pdfFindPanel=panel;
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(12),dp(8),dp(12),dp(10));
    GradientDrawable bg=new GradientDrawable();
    bg.setColor(Color.rgb(45,48,55));bg.setCornerRadius(dp(12));
    bg.setStroke(dp(1),Color.rgb(91,99,105));
    panel.setBackground(bg);panel.setElevation(dp(12));
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading=new TextView(this);
    heading.setText("☰  Find in PDF  ·  Drag to move");
    heading.setTextColor(Color.WHITE);heading.setTextSize(13);
    heading.setPadding(dp(4),dp(6),0,dp(6));
    header.addView(heading,new LinearLayout.LayoutParams(0,dp(36),1));
    Button close=new Button(this);close.setText("×");close.setAllCaps(false);
    close.setTextColor(Color.WHITE);close.setTextSize(20);
    close.setPadding(0,0,0,0);close.setMinWidth(0);close.setMinimumWidth(0);
    close.setBackgroundColor(Color.TRANSPARENT);
    header.addView(close,new LinearLayout.LayoutParams(dp(40),dp(38)));
    panel.addView(header);
    EditText query=new EditText(this);query.setSingleLine(true);
    query.setTextSize(15);query.setTextColor(Color.WHITE);
    query.setHintTextColor(Color.LTGRAY);query.setHint("Search PDF text");
    query.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    panel.addView(query,new LinearLayout.LayoutParams(-1,dp(48)));
    TextView matchCount=new TextView(this);
    matchCount.setTextColor(Color.rgb(180,225,215));matchCount.setTextSize(12);
    matchCount.setPadding(dp(4),dp(2),dp(4),dp(3));
    matchCount.setText("Enter a term to search pages");panel.addView(matchCount);
    LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    Button previous=new Button(this),next=new Button(this);
    previous.setText("Previous");next.setText("Next");
    previous.setAllCaps(false);next.setAllCaps(false);
    previous.setTextSize(12);next.setTextSize(12);
    actions.addView(previous,new LinearLayout.LayoutParams(0,dp(43),1));
    actions.addView(next,new LinearLayout.LayoutParams(0,dp(43),1));panel.addView(actions);
    FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(panelWidth,-2,Gravity.TOP|Gravity.START);
    position.leftMargin=dp(8);position.topMargin=dp(8);
    pdfViewport.addView(panel,position);
    final ArrayList<Integer> matches=new ArrayList<>();
    final int[] current={-1};
    final Runnable navigate=()->{
      if(current[0]<0||current[0]>=matches.size()||!pdfVisible)return;
      pdfHighlightTerm=query.getText().toString().trim();
      pdfPage=matches.get(current[0]);renderPdfPage();
      matchCount.setText((current[0]+1)+" of "+matches.size()+" matching pages");
    };
    previous.setOnClickListener(v->{if(matches.isEmpty())return;
      current[0]=(current[0]-1+matches.size())%matches.size();navigate.run();});
    next.setOnClickListener(v->{if(matches.isEmpty())return;
      current[0]=(current[0]+1)%matches.size();navigate.run();});
    close.setOnClickListener(v->hidePdfSearch());
    query.addTextChangedListener(new android.text.TextWatcher(){
      public void beforeTextChanged(CharSequence t,int st,int count,int after){}
      public void onTextChanged(CharSequence t,int st,int before,int count){
        matches.clear();current[0]=-1;
        String term=t.toString().trim();
        pdfHighlightTerm=term;
        if(pdfRenderer!=null)renderPdfPage();
        if(term.isEmpty()){++pdfSearchGeneration;matchCount.setText("Enter a term to search pages");return;}
        matchCount.setText("Searching pages…");
        searchPdfText(term,panel,matches,current,matchCount,navigate);
      }
      public void afterTextChanged(android.text.Editable e){}
    });
    heading.setOnTouchListener(new View.OnTouchListener(){
      float downX,downY;int initialX,initialY;
      @Override public boolean onTouch(View v,android.view.MotionEvent event){
        switch(event.getActionMasked()){
          case android.view.MotionEvent.ACTION_DOWN:
            downX=event.getRawX();downY=event.getRawY();
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)panel.getLayoutParams();
            initialX=lp.leftMargin;initialY=lp.topMargin;return true;
          case android.view.MotionEvent.ACTION_MOVE:
            FrameLayout.LayoutParams moveLp=(FrameLayout.LayoutParams)panel.getLayoutParams();
            int maxX=Math.max(0,pdfViewport.getWidth()-panel.getWidth());
            int maxY=Math.max(0,pdfViewport.getHeight()-panel.getHeight());
            moveLp.leftMargin=Math.max(0,Math.min(maxX,initialX+Math.round(event.getRawX()-downX)));
            moveLp.topMargin=Math.max(0,Math.min(maxY,initialY+Math.round(event.getRawY()-downY)));
            panel.setLayoutParams(moveLp);return true;
          case android.view.MotionEvent.ACTION_UP:
          case android.view.MotionEvent.ACTION_CANCEL:return true;
        }
        return false;
      }
    });
    query.requestFocus();
    query.post(()->((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
      .showSoftInput(query,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
  }
  void searchPdfText(String query,LinearLayout panel,ArrayList<Integer> matches,int[] current,
                     TextView matchCount,Runnable navigate){
    if(query.isEmpty()||pdfRenderer==null)return;
    final int generation=++pdfSearchGeneration;
    final int documentGeneration=pdfGeneration;
    final int pageCount=pdfRenderer.getPageCount();
    final String lower=query.toLowerCase(Locale.ROOT);
    final Handler handler=new Handler(Looper.getMainLooper());
    final int[] pageIndex={0};
    Runnable task=new Runnable(){
      @Override public void run(){
        if(generation!=pdfSearchGeneration||documentGeneration!=pdfGeneration||
           !pdfVisible||pdfRenderer==null||pdfFindPanel!=panel)return;
        int processed=0;
        while(pageIndex[0]<pageCount&&processed++<2){
          int p=pageIndex[0]++;
          String content=extractPdfPageText(p);
          if(content.toLowerCase(Locale.ROOT).contains(lower))matches.add(p);
        }
        if(pageIndex[0]<pageCount){
          matchCount.setText("Searching page "+pageIndex[0]+" of "+pageCount+"…");
          handler.post(this);return;
        }
        if(matches.isEmpty())matchCount.setText("No matching text found (scans may be image-only)");
        else{current[0]=0;navigate.run();}
      }
    };
    handler.post(task);
  }
  // Text bounds may be Rect or RectF depending on the Android PDF implementation.
  android.graphics.RectF pdfTextRect(Object value){
    if(value instanceof android.graphics.RectF)return new android.graphics.RectF((android.graphics.RectF)value);
    if(value instanceof android.graphics.Rect)return new android.graphics.RectF((android.graphics.Rect)value);
    return null;
  }
  // Locate matches in PDF PAGE coordinates; paint them in a separate transparent
  // View rather than into the rendered PDF bitmap. The overlay shares the exact
  // page dimensions, scrolling, and zoom with the PDF image.
  java.util.ArrayList<android.graphics.RectF> pdfMatchBounds(PdfRenderer.Page page,String query){
    java.util.ArrayList<android.graphics.RectF> found=new java.util.ArrayList<>();
    if(query==null||query.trim().isEmpty()||Build.VERSION.SDK_INT<35)return found;
    // Prefer Android's own search hit geometry. Its rectangles are already
    // associated with the exact matched text, unlike whole text-run bounds.
    try{
      for(java.lang.reflect.Method method:page.getClass().getMethods()){
        if(!method.getName().equals("searchText"))continue;
        Class<?>[] args=method.getParameterTypes();
        if(args.length<1||args[0]!=String.class)continue;
        Object[] values=new Object[args.length];values[0]=query;
        boolean supported=true;
        for(int i=1;i<args.length;i++){
          if(args[i].isPrimitive()){supported=false;break;}
          try{values[i]=args[i].getConstructor().newInstance();}
          catch(Exception e){
            // Some Android builds expose a SearchParams.Builder.
            try{Class<?> builder=Class.forName(args[i].getName()+"$Builder");
              values[i]=builder.getMethod("build").invoke(builder.getConstructor().newInstance());}
            catch(Exception ignored){values[i]=null;}
          }
        }
        if(!supported)continue;
        Object results=method.invoke(page,values);
        if(results instanceof java.util.List){
          for(Object match:(java.util.List<?>)results){
            if(match==null)continue;
            Object rects=match.getClass().getMethod("getBounds").invoke(match);
            if(rects instanceof java.util.List)for(Object box:(java.util.List<?>)rects){
              android.graphics.RectF rect=pdfTextRect(box);
              if(rect!=null&&!rect.isEmpty())found.add(rect);
            }
          }
          if(!found.isEmpty())return found;
        }
      }
    }catch(Exception e){android.util.Log.w("VeilocityPDF","Platform search geometry unavailable",e);}
    // Fallback for devices exposing text bounds but not searchText.
    try{
      Object contents=page.getClass().getMethod("getTextContents").invoke(page);
      if(!(contents instanceof java.util.List))return found;
      StringBuilder full=new StringBuilder();
      java.util.ArrayList<String> texts=new java.util.ArrayList<>();
      java.util.ArrayList<java.util.List<?>> boxes=new java.util.ArrayList<>();
      for(Object item:(java.util.List<?>)contents){
        if(item==null)continue;
        Object value=item.getClass().getMethod("getText").invoke(item);
        if(!(value instanceof String)||((String)value).isEmpty())continue;
        String str=(String)value;texts.add(str);
        Object raw=item.getClass().getMethod("getBounds").invoke(item);
        boxes.add(raw instanceof java.util.List?(java.util.List<?>)raw:java.util.Collections.emptyList());
        full.append(str).append(' ');
      }
      String hay=full.toString().toLowerCase(Locale.ROOT);
      String needle=query.trim().toLowerCase(Locale.ROOT);
      for(int pos=hay.indexOf(needle);pos>=0;pos=hay.indexOf(needle,pos+Math.max(1,needle.length()))){
        int offset=0;
        for(int i=0;i<texts.size();i++){
          String value=texts.get(i);
          int from=Math.max(0,pos-offset),to=Math.min(value.length(),pos+needle.length()-offset);
          if(from<to){
            java.util.List<?> bounds=boxes.get(i);
            if(bounds.size()==value.length()){
              for(int j=from;j<to;j++){
                android.graphics.RectF rect=pdfTextRect(bounds.get(j));
                if(rect!=null&&!rect.isEmpty())found.add(rect);
              }
            }else if(bounds.size()==1){
              android.graphics.RectF r=pdfTextRect(bounds.get(0));
              if(r!=null&&!r.isEmpty()){
                float left=r.left+r.width()*from/value.length();
                float right=r.left+r.width()*to/value.length();
                found.add(new android.graphics.RectF(left,r.top,right,r.bottom));
              }
            }else for(Object box:bounds){
              android.graphics.RectF r=pdfTextRect(box);
              if(r!=null&&!r.isEmpty())found.add(r);
            }
          }
          offset+=value.length()+1;
        }
      }
    }catch(Exception e){android.util.Log.w("VeilocityPDF","Fallback PDF text geometry unavailable",e);}
    return found;
  }
  void updatePdfHighlightOverlay(PdfRenderer.Page page,int displayW,int displayH){
    pdfHighlightRects.clear();pdfActiveHighlight=-1;
    if(pdfHighlightOverlay==null)return;
    if(!pdfHighlightTerm.trim().isEmpty()){
      float sx=displayW/(float)page.getWidth(),sy=displayH/(float)page.getHeight();
      for(android.graphics.RectF box:pdfMatchBounds(page,pdfHighlightTerm)){
        android.graphics.RectF r=new android.graphics.RectF(box);
        r.left=Math.max(0,r.left*sx);r.right=Math.min(displayW,r.right*sx);
        r.top=Math.max(0,r.top*sy);r.bottom=Math.min(displayH,r.bottom*sy);
        if(!r.isEmpty())pdfHighlightRects.add(r);
      }
      if(!pdfHighlightRects.isEmpty())pdfActiveHighlight=0;
    }
    pdfHighlightOverlay.invalidate();
  }
  // Floating, draggable page selection matches the PDF Find panel styling.
  void hidePdfPageJump(){
    if(pdfJumpPanel!=null&&pdfViewport!=null)pdfViewport.removeView(pdfJumpPanel);
    pdfJumpPanel=null;
  }
  void showPdfPageJump(){
    if(pdfRenderer==null||pdfViewport==null)return;
    if(pdfJumpPanel!=null){pdfJumpPanel.bringToFront();return;}
    final int pageCount=pdfRenderer.getPageCount();
    final int panelWidth=Math.min(dp(320),getResources().getDisplayMetrics().widthPixels-dp(36));
    final LinearLayout panel=new LinearLayout(this);
    pdfJumpPanel=panel;
    panel.setOrientation(LinearLayout.VERTICAL);
    panel.setPadding(dp(12),dp(8),dp(12),dp(10));
    GradientDrawable bg=new GradientDrawable();
    bg.setColor(Color.rgb(45,48,55));bg.setCornerRadius(dp(12));
    bg.setStroke(dp(1),Color.rgb(91,99,105));
    panel.setBackground(bg);panel.setElevation(dp(12));
    LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading=new TextView(this);
    heading.setText("☰  Go to Page  ·  Drag to move");
    heading.setTextColor(Color.WHITE);heading.setTextSize(13);
    heading.setPadding(dp(4),dp(6),0,dp(6));
    header.addView(heading,new LinearLayout.LayoutParams(0,dp(36),1));
    Button close=new Button(this);close.setText("×");close.setAllCaps(false);
    close.setTextColor(Color.WHITE);close.setTextSize(20);
    close.setPadding(0,0,0,0);close.setMinWidth(0);close.setMinimumWidth(0);
    close.setBackgroundColor(Color.TRANSPARENT);
    header.addView(close,new LinearLayout.LayoutParams(dp(40),dp(38)));
    panel.addView(header);
    EditText input=new EditText(this);
    input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
    input.setSingleLine(true);input.setText(String.valueOf(pdfPage+1));input.selectAll();
    input.setTextColor(Color.WHITE);input.setHintTextColor(Color.LTGRAY);
    input.setTextSize(15);input.setHint("Page number");
    input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
    panel.addView(input,new LinearLayout.LayoutParams(-1,dp(48)));
    TextView status=new TextView(this);
    status.setTextColor(Color.rgb(180,225,215));status.setTextSize(12);
    status.setPadding(dp(4),dp(2),dp(4),dp(3));
    status.setText("Enter a page from 1 to "+pageCount);panel.addView(status);
    LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
    Button cancel=new Button(this),go=new Button(this);
    cancel.setText("Cancel");go.setText("Go to Page");
    cancel.setAllCaps(false);go.setAllCaps(false);
    cancel.setTextSize(12);go.setTextSize(12);
    actions.addView(cancel,new LinearLayout.LayoutParams(0,dp(43),1));
    actions.addView(go,new LinearLayout.LayoutParams(0,dp(43),1));panel.addView(actions);
    FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(panelWidth,-2,Gravity.TOP|Gravity.START);
    position.leftMargin=dp(8);position.topMargin=dp(8);
    pdfViewport.addView(panel,position);
    Runnable jump=()->{
      if(!pdfVisible||pdfRenderer==null||pdfJumpPanel!=panel)return;
      try{
        int page=Integer.parseInt(input.getText().toString().trim());
        if(page<1||page>pdfRenderer.getPageCount()){
          status.setText("Page must be between 1 and "+pdfRenderer.getPageCount());return;
        }
        pdfPage=page-1;renderPdfPage();hidePdfPageJump();
        ((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(input.getWindowToken(),0);
      }catch(NumberFormatException e){status.setText("Enter a valid page number");}
    };
    go.setOnClickListener(v->jump.run());
    input.setOnEditorActionListener((v,actionId,event)->{jump.run();return true;});
    cancel.setOnClickListener(v->hidePdfPageJump());
    close.setOnClickListener(v->hidePdfPageJump());
    heading.setOnTouchListener(new View.OnTouchListener(){
      float downX,downY;int initialX,initialY;
      @Override public boolean onTouch(View v,android.view.MotionEvent event){
        switch(event.getActionMasked()){
          case android.view.MotionEvent.ACTION_DOWN:
            downX=event.getRawX();downY=event.getRawY();
            FrameLayout.LayoutParams lp=(FrameLayout.LayoutParams)panel.getLayoutParams();
            initialX=lp.leftMargin;initialY=lp.topMargin;return true;
          case android.view.MotionEvent.ACTION_MOVE:
            FrameLayout.LayoutParams moveLp=(FrameLayout.LayoutParams)panel.getLayoutParams();
            int maxX=Math.max(0,pdfViewport.getWidth()-panel.getWidth());
            int maxY=Math.max(0,pdfViewport.getHeight()-panel.getHeight());
            moveLp.leftMargin=Math.max(0,Math.min(maxX,initialX+Math.round(event.getRawX()-downX)));
            moveLp.topMargin=Math.max(0,Math.min(maxY,initialY+Math.round(event.getRawY()-downY)));
            panel.setLayoutParams(moveLp);return true;
          case android.view.MotionEvent.ACTION_UP:
          case android.view.MotionEvent.ACTION_CANCEL:return true;
        }
        return false;
      }
    });
    input.requestFocus();
    input.post(()->((android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE))
      .showSoftInput(input,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT));
  }
  void openPdf(String url){
    closePdf();pdfDocumentKey=Uri.parse(url).getEncodedPath();pdfZoom=1f;pdfVisible=true;hideHtmlSearch();htmlViewport.setVisibility(View.GONE);web.setVisibility(View.GONE);pdfPanel.setVisibility(View.VISIBLE);pdfPageLabel.setText("Opening PDF…");
    final int generation=++pdfGeneration;
    new Thread(()->{
      File temp=null;ParcelFileDescriptor fd=null;PdfRenderer renderer=null;
      try{
        String path=Uri.parse(url).getEncodedPath().substring("/files/".length());
        StringBuilder decoded=new StringBuilder();
        for(String seg:path.split("/")){String part=Uri.decode(seg);if(part.equals("..")||part.contains("/")||part.contains("\\"))throw new IOException("Invalid PDF path");if(decoded.length()>0)decoded.append('/');decoded.append(part);}
        Uri source=resolve(decoded.toString());if(source==null)throw new FileNotFoundException("PDF not found");
        temp=File.createTempFile("veilocity-pdf-",".pdf",getCacheDir());
        try(InputStream in=getContentResolver().openInputStream(source);OutputStream out=new FileOutputStream(temp)){
          if(in==null)throw new IOException("Cannot open PDF");byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
        fd=ParcelFileDescriptor.open(temp,ParcelFileDescriptor.MODE_READ_ONLY);renderer=new PdfRenderer(fd);
        final File readyTemp=temp;final ParcelFileDescriptor readyFd=fd;final PdfRenderer readyRenderer=renderer;
        runOnUiThread(()->{
          if(generation!=pdfGeneration||!pdfVisible){try{readyRenderer.close();}catch(Exception ignored){}try{readyFd.close();}catch(Exception ignored){}readyTemp.delete();return;}
          pdfTemp=readyTemp;pdfFd=readyFd;pdfRenderer=readyRenderer;pdfPage=0;renderPdfPage();
        });
      }catch(Exception e){
        if(renderer!=null)try{renderer.close();}catch(Exception ignored){}
        if(fd!=null)try{fd.close();}catch(Exception ignored){}
        if(temp!=null)temp.delete();
        diagnostic("PDF open failed",e);final String message=e.getMessage();runOnUiThread(()->{if(generation==pdfGeneration&&pdfVisible){closePdf();new AlertDialog.Builder(this).setTitle("Unable to open PDF").setMessage(message).setPositiveButton("OK",null).show();}});
      }
    },"veilocity-pdf-load").start();
  }
  void renderPdfPage(){
    if(pdfRenderer==null)return;
    try(PdfRenderer.Page page=pdfRenderer.openPage(pdfPage)){
      // Zoom is relative to fit-to-width. Render with a memory cap, but
      // always size the displayed page to the requested zoom level.
      int viewport=Math.max(dp(320),getResources().getDisplayMetrics().widthPixels-dp(40));
      float fit=(float)viewport/page.getWidth();
      float requestedScale=fit*pdfZoom;
      // Prevent very large PDFs from exhausting device memory (up to ~24 MB bitmap).
      double pixelLimit=6000000.0;
      float memoryScale=(float)Math.sqrt(pixelLimit/((double)page.getWidth()*page.getHeight()));
      // Rendering is memory-limited, but the displayed page must not be.
      // Previously the bitmap cap also capped the ImageView dimensions, making
      // zoom appear stuck after one or two presses. Keep a bounded bitmap while
      // expanding the on-screen page to the requested magnification.
      float renderScale=Math.max(0.05f,Math.min(requestedScale,memoryScale));
      int w=Math.max(1,Math.round(page.getWidth()*renderScale));
      int h=Math.max(1,Math.round(page.getHeight()*renderScale));
      int displayW=Math.max(1,Math.round(page.getWidth()*requestedScale));
      int displayH=Math.max(1,Math.round(page.getHeight()*requestedScale));
      Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);
      page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
      pdfImage.setImageBitmap(bitmap);
      Bitmap previousBitmap=pdfBitmap;pdfBitmap=bitmap;
      if(previousBitmap!=null&&previousBitmap!=bitmap)previousBitmap.recycle();
      pdfPageLayer.setLayoutParams(new android.widget.FrameLayout.LayoutParams(displayW,displayH));
      updatePdfHighlightOverlay(page,displayW,displayH);
      pdfPageLabel.setText((pdfPage+1)+" / "+pdfRenderer.getPageCount()+"  •  Jump");
      updatePdfBookmarkButton();
      pdfVerticalScroll.scrollTo(0,0);pdfHorizontalScroll.scrollTo(0,0);
    }catch(Exception e){diagnostic("PDF page render failed",e);pdfPageLabel.setText("PDF rendering error");}
  }
  void closePdf(){
    hidePdfPageJump();
    hidePdfSearch();
    ++pdfSearchGeneration;
    ++pdfGeneration;pdfVisible=false;
    if(pdfPanel!=null)pdfPanel.setVisibility(View.GONE);
    if(htmlViewport!=null)htmlViewport.setVisibility(View.VISIBLE);
    if(web!=null)web.setVisibility(View.VISIBLE);
    pdfHighlightRects.clear();pdfActiveHighlight=-1;
    if(pdfHighlightOverlay!=null)pdfHighlightOverlay.invalidate();
    if(pdfImage!=null)pdfImage.setImageDrawable(null);
    if(pdfBitmap!=null){pdfBitmap.recycle();pdfBitmap=null;}
    if(pdfRenderer!=null)try{pdfRenderer.close();}catch(Exception ignored){}pdfRenderer=null;
    if(pdfFd!=null)try{pdfFd.close();}catch(Exception ignored){}pdfFd=null;
    if(pdfTemp!=null)pdfTemp.delete();pdfTemp=null;pdfDocumentKey="";
  }
  void applyControlsVisibility(){
    toolbar.setVisibility(controlsVisible ? View.VISIBLE : View.GONE);
    toggleControls.setText(controlsVisible ? "▲  Hide controls" : "▼  Show controls");
  }
  void roundNativeButton(Button b){
    GradientDrawable shape=new GradientDrawable();
    shape.setColor(Color.rgb(36,44,60));
    shape.setCornerRadius(dp(9));
    b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x55FFFFFF),shape,null));
  }
  void button(String t,Runnable a){
    Button b=new Button(this);
    b.setText(t);
    if("⇧".equals(t))b.setContentDescription("Scroll to top of page");
    if("⇩".equals(t))b.setContentDescription("Scroll to bottom of page");
    b.setTextColor(Color.WHITE);
    b.setTextSize(18);
    b.setAllCaps(false);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(0,0,0,0);
    roundNativeButton(b);
    LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,dp(52),1);
    params.setMargins(dp(3),dp(4),dp(3),dp(4));
    toolbar.addView(b,params);
    b.setOnClickListener(v->a.run());
  }
  // .nomedia is a real file, not a display filter. Request SAF write access so
  // the archive can be excluded from Android media scanning without renaming media.
  void chooseFolder(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(i,PICK);}
  @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==PICK&&res==RESULT_OK&&data!=null){
    tree=data.getData();
    int granted=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    try{getContentResolver().takePersistableUriPermission(tree,granted);}catch(SecurityException e){diagnostic("SAF permission persistence",e);}
    getPreferences(0).edit().putString("tree",tree.toString()).apply();
    clearDirectoryCache();stopServer();startServer();ensureNoMediaAsync(tree);warmCourtPhotosAsync();bootstrapArchiveFilesAsync();
  }}
  // Seed only missing starter files in the SAF-selected Arkhive folder.
  // Never overwrite an existing user-modified homepage, downloads page or artwork.
  void bootstrapArchiveFilesAsync(){
    if(tree==null)return;
    final Uri selected=tree;
    new Thread(()->{
      String[] names={"index.html","veilocity-downloads.html","logo.webp","logo2.webp"};
      String[] assets={"starter-index.html","veilocity-downloads.html","starter-logo.webp","starter-logo2.webp"};
      String[] types={"text/html","text/html","image/webp","image/webp"};
      try{
        Uri base=DocumentsContract.buildDocumentUriUsingTree(selected,DocumentsContract.getTreeDocumentId(selected));
        for(int i=0;i<names.length;i++){
          if(child(base,names[i])!=null)continue;
          Uri created=DocumentsContract.createDocument(getContentResolver(),base,types[i],names[i]);
          if(created==null)continue;
          try(InputStream input=getAssets().open(assets[i]);OutputStream output=getContentResolver().openOutputStream(created,"w")){
            if(output==null)throw new IOException("Unable to write "+names[i]);
            byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1)output.write(buffer,0,n);
          }
        }
        clearDirectoryCache();
        runOnUiThread(()->{if(tree!=null&&tree.equals(selected)&&!pdfVisible)showHome();});
      }catch(Exception e){diagnostic("Arkhive starter file setup",e);}
    },"VeilocityArchiveBootstrap").start();
  }
  // Runs outside the UI thread; covers the chosen archive and nested folders.
  // A .nomedia file prevents future MediaStore indexing, but does not guarantee
  // already-indexed thumbnails disappear immediately from gallery apps.
  void ensureNoMediaAsync(Uri selectedTree){
    new Thread(()->{
      int created=0, skipped=0; boolean failed=false;
      try{
        if(!hasTreeWritePermission(selectedTree)){
          runOnUiThread(()->Toast.makeText(this,"Archive selected read-only; .nomedia requires folder write access",Toast.LENGTH_LONG).show());
          return;
        }
        Uri rootDocument=DocumentsContract.buildDocumentUriUsingTree(selectedTree,DocumentsContract.getTreeDocumentId(selectedTree));
        ArrayDeque<Uri> pending=new ArrayDeque<>();pending.add(rootDocument);
        int visited=0;
        while(!pending.isEmpty() && visited<10000){
          Uri folder=pending.removeFirst();visited++;
          boolean exists=false;
          ArrayList<Uri> subfolders=new ArrayList<>();
          Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(selectedTree,DocumentsContract.getDocumentId(folder));
          try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(c!=null)while(c.moveToNext()){
              String name=c.getString(1);
              if(".nomedia".equals(name))exists=true;
              if(DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2)))
                subfolders.add(DocumentsContract.buildDocumentUriUsingTree(selectedTree,c.getString(0)));
            }
          }
          if(!exists){
            Uri marker=DocumentsContract.createDocument(getContentResolver(),folder,"application/octet-stream",".nomedia");
            if(marker!=null)created++;else failed=true;
          }else skipped++;
          pending.addAll(subfolders);
        }
        if(!pending.isEmpty())failed=true;
      }catch(Exception e){failed=true;diagnostic("Could not create .nomedia in archive",e);}
      final int count=created;final boolean incomplete=failed;
      runOnUiThread(()->Toast.makeText(this,incomplete
          ?".nomedia partially applied; check archive folder permissions"
          :"Gallery exclusion enabled ("+count+" .nomedia files added)",Toast.LENGTH_LONG).show());
    },"VeilocityNoMedia").start();
  }
  boolean hasTreeWritePermission(Uri selectedTree){
    for(android.content.UriPermission permission:getContentResolver().getPersistedUriPermissions())
      if(permission.getUri().equals(selectedTree)&&permission.isWritePermission())return true;
    return false;
  }
  void startServer(){try{socket=new ServerSocket(0,50,InetAddress.getByName("127.0.0.1"));port=socket.getLocalPort();// Restore v2.5.6 request concurrency: video range requests must not wait behind archive requests.
    pool=Executors.newCachedThreadPool();final ServerSocket activeSocket=socket;pool.submit(()->{while(!activeSocket.isClosed()){try{Socket c=activeSocket.accept();pool.submit(()->serve(c));}catch(IOException e){break;}}});showHome();}catch(Exception e){new AlertDialog.Builder(this).setMessage(e.toString()).setPositiveButton("OK",null).show();}}
  void stopServer(){try{if(socket!=null)socket.close();}catch(Exception ignored){}if(pool!=null)pool.shutdownNow();}
  Uri child(Uri parent,String name){Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));try(Cursor c=getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){if(c!=null)while(c.moveToNext())if(name.equals(c.getString(1)))return DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));}return null;}
  Uri resolve(String path){if(tree==null)return null;Uri at=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));if(path.isEmpty())return at;for(String p:path.split("/")){if(p.isEmpty()||p.equals("."))continue;if(p.equals(".."))return null;at=child(at,p);if(at==null)return null;}return at;}
  List<String> names(Uri dir){
    if(dir==null)return new ArrayList<>();
    String key=dir.toString();long now=android.os.SystemClock.elapsedRealtime();
    DirectorySnapshot cached=directoryCache.get(key);
    if(cached!=null&&now-cached.created<DIRECTORY_CACHE_MS)return new ArrayList<>(cached.entries);
    List<String> out=new ArrayList<>();
    Uri u=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(dir));
    try(Cursor c=getContentResolver().query(u,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},null,null,null)){
      if(c!=null)while(c.moveToNext())out.add(c.getString(0));
    }
    Collections.sort(out,String.CASE_INSENSITIVE_ORDER);
    directoryCache.put(key,new DirectorySnapshot(now,new ArrayList<>(out)));
    return out;
  }
  String read(Uri u)throws Exception{try(InputStream in=getContentResolver().openInputStream(u)){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)o.write(b,0,n);return o.toString("UTF-8");}}
  String encode(String s){try{return java.net.URLEncoder.encode(s,"UTF-8").replace("+","%20");}catch(Exception e){return s;}}
  String encodePath(String path){StringBuilder b=new StringBuilder();for(String segment:path.split("/")){if(b.length()>0)b.append("/");b.append(encode(segment));}return b.toString();}
  String esc(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
  String homeHtml(){StringBuilder h=new StringBuilder("<!doctype html><meta name='viewport' content='width=device-width,initial-scale=1'><style>body{background:#0d1119;color:#eaf0fa;font:16px system-ui;padding:22px}h1{color:#73c9ff}a{display:block;padding:16px;margin:9px 0;border-radius:12px;background:#202b3e;color:white;text-decoration:none}small{color:#9caec4}section{margin-top:24px}</style><h1>ARKHIVE</h1><small>Offline multilingual archive browser</small>");
    if(tree==null)return h.append("<p>Select an archive folder using the folder button above.</p>").toString();
    try{Uri base=resolve("");List<String> entries=names(base);h.append("<section><h2>Archives</h2>");
      for(String folder:entries){Uri dir=child(base,folder);if(dir==null)continue;List<String> files;try{files=names(dir);}catch(Exception e){continue;}String primary="";
        if(files.contains("manifest.js")){try{String manifest=read(child(dir,"manifest.js"));java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\"primary_html\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(manifest);if(m.find())primary=m.group(1);}catch(Exception ignored){}}
        if(primary.isEmpty())for(String f:files)if(f.toLowerCase(Locale.ROOT).endsWith(".html")&&!f.toLowerCase(Locale.ROOT).contains("court")){primary=f;break;}
        if(primary.isEmpty())for(String f:files)if(f.toLowerCase(Locale.ROOT).endsWith(".html")){primary=f;break;}
        if(!primary.isEmpty())h.append("<a href='/files/").append(encode(folder)).append("/").append(encodePath(primary)).append("'>").append(esc(folder)).append("<br><small>").append(esc(primary)).append("</small></a>");
      }h.append("</section><section><h2>Root HTML files</h2>");for(String f:entries)if(f.toLowerCase(Locale.ROOT).endsWith(".html"))h.append("<a href='/files/").append(encode(f)).append("'>").append(esc(f)).append("</a>");h.append("</section>");
    }catch(Exception e){h.append("<p>Unable to read folder: ").append(esc(e.toString())).append("</p>");}return h.toString();}
  // Reuse the existing landing page in WebView history instead of loading a fresh
  // index.html on every Home press (which re-runs country discovery and rebuilds cards).
  void returnToHome(){
    title.setText("ARKHIVE");
    if(tree!=null&&web!=null){
      WebBackForwardList history=web.copyBackForwardList();
      int current=history.getCurrentIndex();
      for(int i=current;i>=0;i--){
        WebHistoryItem item=history.getItemAtIndex(i);
        if(item==null)continue;
        String url=item.getUrl();
        if(url!=null&&url.startsWith("http://127.0.0.1:"+port+"/files/index.html")){
          if(i!=current)web.goBackOrForward(i-current);
          return;
        }
      }
    }
    showHome();
  }
  void showHome(){
    title.setText("ARKHIVE");
    // The bundled homepage works even before a folder is selected.
    // The local HTTP server provides its assets and virtual archive index.
    if(port>0)web.loadUrl("http://127.0.0.1:"+port+"/files/index.html");
  }
  // Serve the original Veilocity landing page even if the selected folder has no index.html.
  byte[] bundledLanding() throws IOException {
    try(InputStream in=getAssets().open("starter-index.html")){
      ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;
      while((n=in.read(chunk))!=-1)b.write(chunk,0,n);
      String html=injectLibraryButton(new String(b.toByteArray(),StandardCharsets.UTF_8));
      String additions="<script>"+DOWNLOADS_BUTTON_JS+"</script><script>"+EMPTY_ARCHIVE_NOTICE_JS+"</script>";
      if(tree==null){
        additions+="<script>"+FOLDER_GUIDANCE_JS+"</script>";
      }
      return html.replace("</body>",additions+"</body>").getBytes(StandardCharsets.UTF_8);
    }
  }
  // Friendly offline Court empty state, served only when the actual Court HTML is missing.
  // This deliberately leaves installed Court HTML and its attachment links untouched.
  String courtMissingHtml(){
    boolean selected=tree!=null;
    StringBuilder h=new StringBuilder("<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>Court of Illusions — Veilocity</title>"+
      "<style>*{box-sizing:border-box}body{margin:0;background:#f8ead8;color:#51473c;font:16px/1.55 system-ui,-apple-system,sans-serif;padding:24px}main{max-width:860px;margin:auto}h1{color:#307b78;font-size:clamp(1.6rem,5vw,2.2rem);margin:20px 0 6px}.back{color:#307b78;text-decoration:none;font-weight:650}.card{background:#fff6eb;border:1px solid #e9cfb4;border-radius:14px;padding:20px;margin-top:18px}.card h2{color:#307b78;margin:0 0 10px;font-size:1.25rem}.path{display:block;background:#f9e2c9;padding:12px;border-radius:9px;overflow-wrap:anywhere;font:0.9rem/1.6 monospace}.action{display:inline-block;background:#307b78;color:white;border:0;border-radius:22px;padding:11px 18px;margin:12px 8px 0 0;text-decoration:none;font:650 1rem system-ui;cursor:pointer}.guide{display:flex;align-items:center;gap:14px;margin-top:15px;padding:14px;border:1px solid #e9cfb4;border-radius:12px;background:#fffaf3}.folder-icon{flex:none;display:grid;place-items:center;background:#263142;color:#fff;border-radius:9px;width:62px;height:52px;font-size:24px}.muted{color:#786958;font-size:.93rem}</style></head><body><main><a class='back' href='/files/index.html'>← Back to archive</a><h1>Court of Illusions — Trafficking</h1><p>Offline Court archive</p><section class='card'><h2>Court archive not found</h2><p>The Court document is not available in the currently selected Arkhive folder.</p><p>Download and extract the Court archive into:</p><code class='path'>/sdcard/Arkhive/court-of-illusions-trafficking/</code><p class='muted'>Expected document: ENGLISH - Court.html</p><a class='action' href='/files/downloads.html'>Download Court Archive</a>");
    if(selected){
      h.append("<p class='muted'>An Arkhive folder is already selected. Add the Court files there and reopen this page.</p>");
    }
    return h.append("</section></main></body></html>").toString();
  }
  List<String> archiveFolders(){
    List<String> found=new ArrayList<>();
    try{for(String name:names(resolve(""))){
      if(!name.startsWith(".") && !name.equalsIgnoreCase("court-of-illusions-trafficking") && !name.equalsIgnoreCase("1_Library") && !name.equalsIgnoreCase("Arkchive Multilingual Frontend") && !name.equalsIgnoreCase("Archive Multilingual Frontend") && !name.equalsIgnoreCase("assets") && !name.equalsIgnoreCase("images") && !name.endsWith("_Files")){
        Uri dir=resolve(name);if(dir==null)continue;
        try{List<String> files=names(dir);for(String f:files)if((f.toLowerCase(Locale.ROOT).endsWith(".html")||f.toLowerCase(Locale.ROOT).endsWith(".htm"))&&!f.toLowerCase(Locale.ROOT).contains("court")){found.add(name);break;}}catch(Exception ignored){}
      }
    }}catch(Exception ignored){}
    return found;
  }
  String jsQuote(String s){return org.json.JSONObject.quote(s);}
  String virtualArchives(){
    StringBuilder b=new StringBuilder("window.VEILOCITY_ARCHIVES={archives:[");
    List<String> folders=archiveFolders();
    for(int i=0;i<folders.size();i++){if(i>0)b.append(',');b.append(jsQuote(folders.get(i)));}
    b.append("],logo:");
    String logo="";
    try{List<String> rootFiles=names(resolve(""));if(rootFiles.contains("logo.png"))logo="logo.png";
      else for(String f:rootFiles)if(f.matches("(?i)(veilocity-logo|veilocity_logo|veilocity)\\.(png|jpg|jpeg|webp|gif)")){logo=f;break;}
    }catch(Exception ignored){}
    // The APK bundles logo.png, even when the selected folder does not.
    if (logo.isEmpty()) logo="logo.webp";
    b.append("{folder:\".\",file:").append(jsQuote(logo)).append("}");
    return b.append("};").toString();
  }
  String virtualManifest(String folder){
    StringBuilder b=new StringBuilder("window.VEILOCITY_MANIFESTS=window.VEILOCITY_MANIFESTS||{};window.VEILOCITY_MANIFESTS[");
    b.append(jsQuote(folder)).append("]={");
    List<String> files=new ArrayList<>();
    try{files=names(resolve(folder));}catch(Exception ignored){}
    String name=folder.replaceFirst("(?i)^ark\\s+","").replaceFirst("(?i)\\s+v[0-9]+$","");
    String ver="";java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?i)\\s+(v[0-9]+)$").matcher(folder);if(m.find())ver=m.group(1);
    String primary="",flag="";
    for(String f:files){String lower=f.toLowerCase(Locale.ROOT);
      if(primary.isEmpty()&&(lower.endsWith(".html")||lower.endsWith(".htm"))&&!lower.contains("court"))primary=f;
      if(flag.isEmpty()&&lower.contains("flag")&&(lower.endsWith(".png")||lower.endsWith(".jpg")||lower.endsWith(".webp")||lower.endsWith(".gif")))flag=f;
    }
    for(String f:files)if(f.equalsIgnoreCase(name+".html"))primary=f;
    b.append("country:").append(jsQuote(name)).append(",language:").append(jsQuote(name)).append(",version:").append(jsQuote(ver));
    b.append(",flag:").append(jsQuote(flag)).append(",primary_html:").append(jsQuote(primary));
    b.append(",description:").append(jsQuote(name+" portable archive")).append(",files:[");
    for(int i=0;i<files.size();i++){if(i>0)b.append(',');b.append(jsQuote(files.get(i)));}
    return b.append("]};").toString();
  }
  // Browse nested 1_Library folders without requiring generated index.html files.
  // Virtual _browse.html routes are confined to 1_Library and checked via SAF.
  boolean isDirectory(Uri document){
    try(Cursor c=getContentResolver().query(document,
      new String[]{DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
      return c!=null&&c.moveToFirst()&&DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(0));
    }catch(Exception ignored){return false;}
  }
  // Display-only normalization: never modify SAF names or URL targets.
  String libraryDisplayName(String name){return name.replace('_',' ');}
  String libraryHtml(String relative){
    StringBuilder h=new StringBuilder("<!doctype html><html lang='en'><head><meta name='viewport' content='width=device-width,initial-scale=1'><meta charset='utf-8'><title>Veilocity Library</title>"+
      "<style>*{box-sizing:border-box}body{margin:0;background:#f8ead8;color:#51473c;font:16px/1.55 system-ui,-apple-system,sans-serif;padding:24px;overflow-x:hidden}main{max-width:860px;margin:auto;min-width:0;overflow-wrap:anywhere}h1{color:#307b78;font-size:clamp(1.6rem,5vw,2.2rem);margin:20px 0 6px}.back{color:#307b78;text-decoration:none;font-weight:650}.card{background:#fff6eb;border:1px solid #e9cfb4;border-radius:14px;padding:20px;margin-top:18px}.card h2{color:#307b78;margin:0 0 10px;font-size:1.25rem}.path{display:block;background:#f9e2c9;padding:12px;border-radius:9px;overflow-wrap:anywhere;font:0.9rem/1.6 monospace}.action{display:inline-block;background:#307b78;color:white;border:0;border-radius:22px;padding:11px 18px;margin:12px 8px 0 0;text-decoration:none;font:650 1rem system-ui;cursor:pointer}.muted{color:#786958;font-size:.93rem}li{background:#fff6eb;border:1px solid #e9cfb4;border-radius:12px;padding:12px;margin:8px 0;list-style:none;max-width:100%;min-width:0;overflow-wrap:anywhere;word-break:break-word}li a{display:block;max-width:100%;overflow-wrap:anywhere;word-break:break-word;color:#307b78;text-decoration:none}ul{padding:0}.folder{font-weight:650}a{color:#307b78;text-decoration:none}</style></head><body><main><a class='back' href='/files/index.html'>← Back to archive</a><h1>Library</h1>");
    if(!relative.isEmpty()){
      int slash=relative.lastIndexOf('/');
      String parent=slash<0?"":relative.substring(0,slash);
      h.append("<p><a href='/files/1_Library/").append(parent.isEmpty()?"":encodePath(parent)+"/").append("_browse.html'>← Parent folder</a></p>");
      h.append("<p>Folder: ").append(esc(libraryDisplayName(relative))).append("</p>");
    }else h.append("<p>Offline PDF, spreadsheet and image collection</p>");
    h.append("<ul>");
    try{
      Uri folder=resolve("1_Library"+(relative.isEmpty()?"":"/"+relative));
      if(folder==null||!isDirectory(folder)){
        h.append("</ul><section class='card'><h2>Library folder not found</h2><p>The offline PDF and image collection is not available in the currently selected Arkhive folder.</p><p>Create or copy your Library folder into:</p><code class='path'>/sdcard/Arkhive/1_Library/</code><p class='muted'>Place your PDFs, XLSX spreadsheets, images and subfolders inside 1_Library.</p><a class='action' href='/files/downloads.html'>Download Archives</a></section><ul>");
      }
      else{
        int count=0;
        // Preserve alphabetical ordering within each group, with folders first.
        List<String> folderNames=new ArrayList<>();
        List<String> pdfNames=new ArrayList<>();
        List<String> imageNames=new ArrayList<>();
        List<String> spreadsheetNames=new ArrayList<>();
        List<String> officeNames=new ArrayList<>();
        for(String name:names(folder)){
          Uri item=child(folder,name);
          if(item==null)continue;
          if(isDirectory(item))folderNames.add(name);
          else if(name.toLowerCase(Locale.ROOT).endsWith(".pdf"))pdfNames.add(name);
          else if(name.toLowerCase(Locale.ROOT).endsWith(".xlsx"))spreadsheetNames.add(name);
          else if(name.toLowerCase(Locale.ROOT).matches(".*\\.(docx|doc|msg)$"))officeNames.add(name);
          else if(name.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp|avif)$"))imageNames.add(name);
        }
        for(String name:folderNames){
          String itemPath=relative.isEmpty()?name:relative+"/"+name;
          h.append("<li><a class='folder' href='/files/1_Library/").append(encodePath(itemPath)).append("/_browse.html'>📁 ").append(esc(libraryDisplayName(name))).append("/</a></li>");count++;
        }
        for(String name:pdfNames){
          String itemPath=relative.isEmpty()?name:relative+"/"+name;
          h.append("<li><a href='/files/1_Library/").append(encodePath(itemPath)).append("'>📄 ").append(esc(libraryDisplayName(name))).append("</a></li>");count++;
        }
        for(String name:spreadsheetNames){
          String itemPath=relative.isEmpty()?name:relative+"/"+name;
          h.append("<li><a href='/files/1_Library/").append(encodePath(itemPath)).append("'>📊 ").append(esc(libraryDisplayName(name))).append("</a></li>");count++;
        }
        for(String name:officeNames){
          String itemPath=relative.isEmpty()?name:relative+"/"+name;
          h.append("<li><a href='/files/1_Library/").append(encodePath(itemPath)).append("'>📝 ").append(esc(libraryDisplayName(name))).append("</a></li>");count++;
        }
        for(String name:imageNames){
          String itemPath=relative.isEmpty()?name:relative+"/"+name;
          h.append("<li><a href='/files/1_Library/").append(encodePath(itemPath)).append("'>🖼️ ").append(esc(libraryDisplayName(name))).append("</a></li>");count++;
        }
        if(count==0)h.append("<li>No subfolders, PDFs, spreadsheets, or images found here.</li>");
      }
    }catch(Exception e){h.append("<li>Unable to read the library.</li>");}
    return h.append("</ul></main></body></html>").toString();
  }
  // Self-contained offline image viewer, used by Library and Court attachments.
  String imageViewerHtml(String relative){return imageViewerHtml(relative,true);}
  String imageViewerHtml(String relative, boolean library){
    String imageUrl="/files/"+(library?"1_Library/":"")+encodePath(relative);
    String safeUrl=esc(imageUrl);
    String safeName=esc(relative.substring(relative.lastIndexOf('/')+1));
    return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes'>"+
      "<title>"+safeName+" — Veilocity</title><style>"+
      "*{box-sizing:border-box}html,body{margin:0;height:100%;background:#111722;color:#fff;font:15px system-ui}"+
      "header{display:flex;align-items:center;gap:8px;padding:10px;background:#252e3d;flex-wrap:wrap}"+
      "button{border:0;border-radius:9px;padding:10px 14px;background:#307b78;color:white;font:600 16px system-ui}"+
      "#name{flex:1;min-width:110px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"+
      "#stage{position:relative;width:100%;height:calc(100% - 66px);overflow:hidden;touch-action:none;user-select:none;cursor:grab}"+
      "#picture{position:absolute;max-width:none;max-height:none;transform-origin:0 0;pointer-events:none}"+
      "</style></head><body><header><span id='name'>"+safeName+"</span>"+
      "<button id='minus' aria-label='Zoom out'>−</button><button id='fit'>Fit</button>"+
      "<button id='plus' aria-label='Zoom in'>+</button><span id='percent'>100%</span></header>"+
      "<div id='stage'><img id='picture' src='"+safeUrl+"' alt='"+safeName+"'></div>"+
      "<script>(function(){const stage=document.getElementById('stage'),img=document.getElementById('picture'),pct=document.getElementById('percent');"+
      "let scale=1,fitScale=1,x=0,y=0;const touches=new Map();let lastDist=0,lastMid=null;"+
      "function draw(){img.style.transform='translate('+x+'px,'+y+'px) scale('+scale+')';pct.textContent=Math.round(scale/fitScale*100)+'%';}"+
      "function fit(){if(!img.naturalWidth)return;fitScale=Math.min(stage.clientWidth/img.naturalWidth,stage.clientHeight/img.naturalHeight,1);scale=fitScale;x=(stage.clientWidth-img.naturalWidth*scale)/2;y=(stage.clientHeight-img.naturalHeight*scale)/2;draw();}"+
      "function zoom(f,cx,cy){let ns=Math.max(fitScale*.5,Math.min(fitScale*8,scale*f));let ratio=ns/scale;x=cx-(cx-x)*ratio;y=cy-(cy-y)*ratio;scale=ns;draw();}"+
      "document.getElementById('minus').onclick=()=>zoom(1/1.25,stage.clientWidth/2,stage.clientHeight/2);"+
      "document.getElementById('plus').onclick=()=>zoom(1.25,stage.clientWidth/2,stage.clientHeight/2);"+
      "document.getElementById('fit').onclick=fit;img.onload=fit;window.addEventListener('resize',fit);"+
      "stage.addEventListener('pointerdown',e=>{stage.setPointerCapture(e.pointerId);touches.set(e.pointerId,{x:e.clientX,y:e.clientY});lastDist=0;});"+
      "stage.addEventListener('pointermove',e=>{if(!touches.has(e.pointerId))return;let old=touches.get(e.pointerId);touches.set(e.pointerId,{x:e.clientX,y:e.clientY});"+
      "if(touches.size===1){x+=e.clientX-old.x;y+=e.clientY-old.y;draw();}else if(touches.size===2){let pts=[...touches.values()],d=Math.hypot(pts[0].x-pts[1].x,pts[0].y-pts[1].y);"+
      "let mid={x:(pts[0].x+pts[1].x)/2-stage.getBoundingClientRect().left,y:(pts[0].y+pts[1].y)/2-stage.getBoundingClientRect().top};"+
      "if(lastDist>0){zoom(d/lastDist,mid.x,mid.y);if(lastMid){x+=mid.x-lastMid.x;y+=mid.y-lastMid.y;draw();}}lastDist=d;lastMid=mid;}});"+
      "function end(e){touches.delete(e.pointerId);lastDist=0;lastMid=null;}stage.addEventListener('pointerup',end);stage.addEventListener('pointercancel',end);"+
      "stage.addEventListener('wheel',e=>{e.preventDefault();let r=stage.getBoundingClientRect();zoom(e.deltaY<0?1.15:1/1.15,e.clientX-r.left,e.clientY-r.top);},{passive:false});"+
      "})();</script></body></html>";
  }
  String mime(String p){String x=p.toLowerCase(Locale.ROOT);if(x.endsWith(".html")||x.endsWith(".htm"))return "text/html";if(x.endsWith(".js"))return "text/javascript";if(x.endsWith(".css"))return "text/css";if(x.endsWith(".svg"))return "image/svg+xml";if(x.endsWith(".png"))return "image/png";if(x.endsWith(".bmp"))return "image/bmp";if(x.endsWith(".avif"))return "image/avif";if(x.endsWith(".jpg")||x.endsWith(".jpeg"))return "image/jpeg";if(x.endsWith(".webp"))return "image/webp";if(x.endsWith(".gif"))return "image/gif";if(x.endsWith(".json"))return "application/json";if(x.endsWith(".pdf"))return "application/pdf";if(x.endsWith(".mp4"))return "video/mp4";if(x.endsWith(".mp3"))return "audio/mpeg";if(x.endsWith(".webm"))return "video/webm";if(x.endsWith(".ico"))return "image/x-icon";if(x.endsWith(".woff2"))return "font/woff2";if(x.endsWith(".woff"))return "font/woff";return "application/octet-stream";}
  static final String CLICK_TO_LOAD_JS =
    "(function(){\n" +
    "'use strict';\n" +
    "const prepared=new WeakSet();\n" +
    "// Keep at most one HTML5 video playing in this document.\n" +
    "function pauseOthers(active){document.querySelectorAll('video').forEach(v=>{if(v!==active&&!v.paused){try{v.pause();}catch(e){}}});}\n" +
    "document.addEventListener('play',function(e){if(e.target&&e.target.tagName==='VIDEO')pauseOthers(e.target);},true);\n" +
    "function prepare(video){\n" +
    " if(prepared.has(video)||video.dataset.arkhiveActivated==='1')return;\n" +
    " prepared.add(video);\n" +
    " const sources=Array.from(video.querySelectorAll('source'));\n" +
    " const own=video.getAttribute('src');\n" +
    " const saved=sources.map(s=>({node:s,src:s.getAttribute('src'),srcset:s.getAttribute('srcset')}));\n" +
    " if(!own&&!saved.some(x=>x.src))return;\n" +
    " video.pause();video.removeAttribute('autoplay');video.autoplay=false;\n" +
    " video.setAttribute('preload','none');video.preload='none';\n" +
    " video.removeAttribute('src');\n" +
    " saved.forEach(x=>{x.node.removeAttribute('src');x.node.removeAttribute('srcset');});\n" +
    " video.load();\n" +
    " video.controls=false;video.removeAttribute('controls');\n" +
    " const parent=video.parentElement;if(!parent)return;\n" +
    " const wrap=document.createElement('div');wrap.className='arkhive-video-wrap';\n" +
    " wrap.style.cssText='position:relative;display:block;width:fit-content;max-width:100%;margin-bottom:0;isolation:isolate;';\n" +
    " parent.insertBefore(wrap,video);wrap.appendChild(video);\n" +
    " if(parent.classList.contains('chatlog__attachment')){parent.style.marginBottom='22px';parent.style.background='transparent';parent.style.boxShadow='none';parent.style.border='0';}else{wrap.style.marginBottom='22px';}\n" +
    " video.style.display='block';video.style.marginBottom='0';video.style.verticalAlign='top';\n" +
    " const cover=document.createElement('div');cover.className='arkhive-video-cover';\n" +
    " cover.style.cssText='position:absolute;inset:0;z-index:9;border-radius:9px;background:linear-gradient(145deg,#27303b,#171d26);display:flex;align-items:center;justify-content:center;pointer-events:none;';\n" +
    " wrap.appendChild(cover);\n" +
    " const button=document.createElement('button');button.type='button';\n" +
    " button.innerHTML='&#9654;';button.setAttribute('aria-label','Load and play video');button.setAttribute('title','Play video');\n" +
    " button.style.cssText='position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);z-index:10;background:#354b50;color:#e5f0ed;border:1px solid #73918e;border-radius:50%;width:58px;height:58px;padding:0 0 0 3px;font:600 27px system-ui;cursor:pointer;box-shadow:0 3px 12px rgba(0,0,0,.28);display:flex;align-items:center;justify-content:center;';\n" +
    " wrap.appendChild(button);\n" +
    " button.addEventListener('click',function(){\n" +
    "  button.disabled=true;button.innerHTML='&#8987;';\n" +
    "  pauseOthers(video);\n" +
    "  video.dataset.arkhiveActivated='1';\n" +
    "  if(own!==null)video.setAttribute('src',own);\n" +
    "  saved.forEach(x=>{if(x.src!==null)x.node.setAttribute('src',x.src);if(x.srcset!==null)x.node.setAttribute('srcset',x.srcset);});\n" +
    "  video.setAttribute('controls','');video.controls=true;\n" +
    "  video.preload='metadata';video.load();\n" +
    "  const attempt=video.play();if(attempt&&attempt.catch)attempt.catch(()=>{});\n" +
    "  cover.remove();button.remove();\n" +
    " },{once:true});\n" +
    "}\n" +
    "function scan(node){if(!node||node.nodeType!==1)return;if(node.matches&&node.matches('video'))prepare(node);if(node.querySelectorAll)node.querySelectorAll('video').forEach(prepare);}\n" +
    "function start(){scan(document.body);new MutationObserver(mutations=>{for(const m of mutations){m.addedNodes.forEach(scan);if(m.type==='attributes'&&m.target.tagName==='VIDEO')scan(m.target);}}).observe(document.body,{childList:true,subtree:true});}\n" +
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start,{once:true});else start();\n" +
    "})();\n";
  static final String LIBRARY_BUTTON_JS =
    "(function(){function add(){if(document.getElementById('veilocity-library-link'))return;"+
    "var court=Array.from(document.querySelectorAll('a,button')).find(function(e){return /court of illusions/i.test(e.textContent||'')});"+
    "var link=document.createElement('a');link.id='veilocity-library-link';link.href='/files/1_Library/index.html';link.textContent='Library';"+
    "if(court){"+
    "var cs=window.getComputedStyle(court),r=court.getBoundingClientRect();"+
    "var wrap=document.createElement('div');wrap.id='veilocity-primary-links';"+
    "wrap.style.cssText='display:flex;flex-direction:column;align-items:stretch;gap:14px;box-sizing:border-box;max-width:100%;';"+
    "if(r.width>0)wrap.style.width=r.width+'px';"+
    "link.className=court.className;link.style.cssText=court.style.cssText;"+
    "['boxSizing','padding','border','borderRadius','background','color','font','fontWeight','textAlign','textDecoration','minHeight'].forEach(function(k){link.style[k]=cs[k]});"+
    "link.style.display='flex';link.style.alignItems='center';link.style.justifyContent='center';"+
    "link.style.width='100%';link.style.maxWidth='100%';link.style.margin='0';"+
    "if(r.height>0)link.style.height=r.height+'px';"+
    "court.parentNode.insertBefore(wrap,court);wrap.appendChild(link);wrap.appendChild(court);"+
    "court.style.width='100%';court.style.maxWidth='100%';court.style.boxSizing='border-box';court.style.margin='0';"+
    "}else{link.style.cssText='display:block;box-sizing:border-box;max-width:440px;margin:0 0 14px;padding:13px 18px;border-radius:12px;background:#307b78;color:white;font:600 16px system-ui;text-align:center;text-decoration:none';"+
    "var search=document.querySelector('.tools,#search,input[type=search]');if(search&&search.parentNode)search.parentNode.insertBefore(link,search);else (document.querySelector('.content')||document.body).appendChild(link);}"+
    "}"+
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',add);else add();})();";
  // Redirect Court entries from every language archive to the shared root document.
  // This is injected at response time: the user's archive files are never changed.
  static final String COURT_REDIRECT_JS =
    "(function(){'use strict';"+
    "var target='/files/court-of-illusions-trafficking/ENGLISH%20-%20Court.html';"+
    "function courtLink(a){if(!a||!a.getAttribute)return false;"+
    "if(a.classList&&a.classList.contains('veilocity-image-attachment'))return false;"+
    "if((a.getAttribute('href')||'').indexOf('/_image_view.html')!==-1)return false;"+
    "var raw=(a.getAttribute('href')||'').toLowerCase();"+
    "if(raw.startsWith('#')||raw.startsWith('javascript:')||raw.startsWith('mailto:'))return false;"+
    "if(/^https?:\\/\\//.test(raw)&&!raw.includes('127.0.0.1'))return false;"+
    "var label=(a.textContent||'').replace(/\\s+/g,' ').trim().toLowerCase();"+
    "return /court.of.illusions|english\\s*[-_]\\s*court\\.html|court-of-illusions-trafficking/i.test(raw)||/court of illusions/i.test(label);}"+
    "function update(root){if(!root||!root.querySelectorAll)return;"+
    "root.querySelectorAll('a[href]').forEach(function(a){if(courtLink(a))a.setAttribute('href',target)});}"+
    "document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[href]');"+
    "if(a&&courtLink(a)){e.preventDefault();window.location.assign(target)}},true);"+
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',function(){update(document);"+
    "new MutationObserver(function(){update(document)}).observe(document.body,{childList:true,subtree:true});});"+
    "else{update(document);new MutationObserver(function(){update(document)}).observe(document.body,{childList:true,subtree:true});}"+
    "})();";
  // Archive attachment links are rendered as generic attachment cards and
  // renamed to "Subtitles" or "MP3" by each exported HTML page on load.
  // Hide only those attachment cards, never the actual audio/video players.
  static final String HIDE_CAPTION_BUTTONS_JS =
    "(function(){'use strict';\n" +
    "function unwanted(el){\n" +
    " var href=(el.getAttribute('href')||el.getAttribute('data-href')||el.getAttribute('download')||'').split(/[?#]/)[0];\n" +
    " var label=(el.textContent||el.getAttribute('aria-label')||el.getAttribute('title')||'').replace(/\\s+/g,' ').trim();\n" +
    " return /\\.(srt|vtt|mp3)(?:$|[?#])/i.test(href)||/^(?:subtitles?|captions?|mp3|download mp3|mp3 download|audio mp3|download subtitles?|download captions?)$/i.test(label);\n" +
    "}\n" +
    "function hide(){\n" +
    " document.querySelectorAll('a,button,[role=\"button\"]').forEach(function(el){\n" +
    "  if(!unwanted(el)||el.closest('audio,video'))return;\n" +
    "  var card=el.closest('.chatlog__attachment-generic');\n" +
    "  var target=card&&card.closest('.chatlog__attachment')||card||el;\n" +
    "  if(target.dataset.veilocityHiddenAttachment==='1')return;\n" +
    "  target.dataset.veilocityHiddenAttachment='1';\n" +
    "  target.style.setProperty('display','none','important');\n" +
    "  target.setAttribute('aria-hidden','true');\n" +
    " });\n" +
    "}\n" +
    "function start(){hide();new MutationObserver(hide).observe(document.body,{childList:true,subtree:true,characterData:true,attributes:true,attributeFilter:['href','title','aria-label','class']});}\n" +
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();\n" +
    "})();\n";
  // Apply the Contact button to every served HTML page, including country index.html files.
  // Keep the original archive HTML and filenames untouched.
  static final String CONTACT_BUTTON_JS =
    "(function(){'use strict';"+
    "if(window.__veilocityContactInstalled)return;window.__veilocityContactInstalled=true;"+
    "var email='mailto:stephencooke.c@gmail.com';"+
    "function isGmail(el){"+
    "var label=[el.textContent,el.getAttribute('aria-label'),el.getAttribute('title'),el.getAttribute('data-label')].join(' ').trim();"+
    "var href=(el.getAttribute('href')||'').trim();"+
    "return /\\bgmail\\b/i.test(label)||/^https?:\\/\\/(?:www\\.)?gmail\\.com(?:\\/|$)/i.test(href)||/^https?:\\/\\/mail\\.google\\.com(?:\\/|$)/i.test(href);"+
    "}"+
    "function update(){document.querySelectorAll('a,button,[role=\"button\"]').forEach(function(el){"+
    "if(!isGmail(el))return;"+
    "el.textContent='Contact';el.setAttribute('aria-label','Contact');el.setAttribute('title','Contact');"+
    "if(el.tagName.toLowerCase()==='a')el.setAttribute('href',email);"+
    "else el.dataset.veilocityContact='1';"+
    "});}"+
    "document.addEventListener('click',function(e){"+
    "var el=e.target.closest&&e.target.closest('a,button,[role=\"button\"]');"+
    "if(el&&(el.dataset.veilocityContact==='1'||el.getAttribute('href')===email)){"+
    "if(el.tagName.toLowerCase()!=='a'){e.preventDefault();e.stopPropagation();location.href=email;}"+
    "}},true);"+
    "var pending=false;function schedule(){if(pending)return;pending=true;requestAnimationFrame(function(){pending=false;update();});}"+
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',function(){update();new MutationObserver(schedule).observe(document.body,{childList:true,subtree:true});});"+
    "else{update();new MutationObserver(schedule).observe(document.body,{childList:true,subtree:true});}"+
    "})();";
  // Add Downloads only when the page does not already provide that link.
  // The bundled homepage includes a static Download Archives link, so do not duplicate it.
  static final String DOWNLOADS_BUTTON_JS =
    "(function(){'use strict';if(window.__veilocityDownloadsInstalled)return;window.__veilocityDownloadsInstalled=true;"+
    "function add(){"+
    "var nav=document.getElementById('veilocity-contact-links')||document.querySelector('.support-contacts')||document.querySelector('.veilocity-contact-links');"+
    "if(!nav){var links=document.querySelectorAll('a[href]');for(var i=0;i<links.length;i++){if(/github[.]com/i.test(links[i].href)){nav=links[i].parentElement;break;}}}"+
    "if(!nav)return;"+
    "var links=nav.querySelectorAll('a[href]');var found=false;"+
    "for(var j=0;j<links.length;j++){var link=links[j];"+
    "var href=(link.getAttribute('href')||'').trim();"+
    "var isDownloads=/(?:^|\\/)(?:veilocity-)?downloads\\.html(?:[?#]|$)/i.test(href);"+
    "isDownloads=isDownloads||/\\bdownload archives\\b/i.test((link.textContent||'').trim());"+
    "if(isDownloads){if(found&&link.id==='veilocity-downloads-link')link.remove();else found=true;}}"+
    "if(found)return;"+
    "var a=document.createElement('a');a.id='veilocity-downloads-link';a.href='/files/downloads.html';a.textContent='Download Archives';nav.appendChild(a);}"+
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',add);else add();"+
    "})();";
  // Visible only when the selected archive contains no country HTML folders.
  static final String EMPTY_ARCHIVE_NOTICE_JS =
    "(function(){function update(){var grid=document.getElementById('grid');if(!grid)return;"+
    "var count=document.getElementById('count');var total=window.VEILOCITY_ARCHIVES&&window.VEILOCITY_ARCHIVES.archives;"+
    "if(!Array.isArray(total)||total.length)return;if(document.getElementById('veilocity-empty-archive'))return;"+
    "var note=document.createElement('section');note.id='veilocity-empty-archive';"+
    "note.style.cssText='background:#fff6eb;border:1px solid #e9cfb4;border-radius:15px;padding:22px;margin:20px 0;color:#51473c';"+
    "note.innerHTML='<h2 style=\"color:#307b78\">Download Country Archives</h2><p>No country archives have been installed yet. Download and extract country folders into <strong>/sdcard/Arkhive/</strong> (or the Arkhive folder selected in the app). Then refresh the homepage.</p><a href=\"/files/downloads.html\" style=\"display:inline-block;background:#307b78;color:white;padding:11px 18px;border-radius:22px;text-decoration:none;font-weight:650\">Download Archives</a>';"+
    "grid.parentNode.insertBefore(note,grid);}"+
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',function(){setTimeout(update,250)});else setTimeout(update,250);})();";
  // Show the folder-selection guidance INSIDE the missing-country card only.
  // This script is injected only when no archive folder has been selected.
  static final String FOLDER_GUIDANCE_JS =
    "(function(){function add(){if(document.getElementById('veilocity-folder-guide'))return;"+
    "var note=document.getElementById('veilocity-empty-archive');if(!note)return;"+
    "var guide=document.createElement('div');guide.id='veilocity-folder-guide';"+
    "guide.style.cssText='display:flex;align-items:center;gap:14px;flex-wrap:wrap;background:#fffaf3;border:1px solid #e9cfb4;border-radius:12px;padding:14px;margin:16px 0;color:#51473c';"+
    "guide.innerHTML='<span aria-label=\"Folder selection icon\" style=\"flex:none;display:grid;place-items:center;width:62px;height:52px;background:#263142;color:white;border-radius:9px;font-size:24px\">▣</span><span style=\"flex:1;min-width:180px\">To connect your offline archives, tap the matching <strong>▣ folder button</strong> in Veilocity&#39;s top control bar.</span><a href=\"veilocity://choose-folder\" style=\"display:inline-block;background:#307b78;color:white;padding:10px 16px;border-radius:22px;text-decoration:none;font-weight:650\">▣ Choose Arkhive Folder</a>';"+
    "var download=note.querySelector('a[href*=\"downloads\"]');if(download)note.insertBefore(guide,download);else note.appendChild(guide);}"+
    "function start(){add();setTimeout(add,350);setTimeout(add,800);}"+
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',start);else start();})();";
  // Country index.html only: remember the indexed section and exact scroll position
  // before opening an image. No synthetic clicks or visible navigation are needed.
  static final String COUNTRY_IMAGE_RETURN_JS =
    "(function(){'use strict';"+
    "if(window.__veilocityCountryReturn)return;window.__veilocityCountryReturn=true;"+
    "var key='veilocity:image-return:'+location.pathname;"+
    "function save(e){"+
    "var a=e.target.closest&&e.target.closest('a[href]');var direct=e.target.closest&&e.target.closest('img');if(!a&&!direct)return;"+
    "var href=a?(a.getAttribute('href')||''):'';var img=direct||(a&&a.querySelector('img'));"+
    "var image=/\\.(?:png|jpe?g|webp|gif|bmp|avif)(?:[?#]|$)/i.test(href)||/\\/_image_view\\.html(?:[?#]|$)/i.test(href);"+
    "if(!image&&!img)return;"+
    "if(/^https?:/i.test(href)&&!href.includes('127.0.0.1'))return;"+
    "var target=(a||direct).closest('[id]'),best=null;"+
    "var anchors=Array.from(document.querySelectorAll('a[href^=\"#\"]'));"+
    "var indexed=new Set(anchors.map(function(x){try{return decodeURIComponent(x.hash.slice(1));}catch(e){return x.hash.slice(1);}}));"+
    "var y=window.scrollY||document.documentElement.scrollTop||0;"+
    "document.querySelectorAll('[id]').forEach(function(el){"+
    "if(!indexed.has(el.id))return;var top=el.getBoundingClientRect().top+y;"+
    "if(top<=y+window.innerHeight*.65&&(!best||top>best.top))best={id:el.id,top:top};"+
    "});"+
    "try{sessionStorage.setItem(key,JSON.stringify({x:window.scrollX,y:y,id:best?best.id:null,at:Date.now()}));}catch(err){}"+
    "}"+
    "function jump(x,y){"+
    "var st=document.createElement('style');st.textContent='html,body,*{scroll-behavior:auto!important}';document.head.appendChild(st);"+
    "window.scrollTo(x,y);document.documentElement.scrollTop=y;document.body.scrollTop=y;"+
    "setTimeout(function(){window.scrollTo(x,y);st.remove();},400);"+
    "}"+
    "function restore(){var data;try{data=JSON.parse(sessionStorage.getItem(key)||'null');}catch(e){}"+
    "if(!data||Date.now()-data.at>1800000)return;"+
    "var x=data.x||0,y=data.y||0;"+
    "jump(x,y);requestAnimationFrame(function(){jump(x,y);});"+
    "setTimeout(function(){jump(x,y);},80);setTimeout(function(){jump(x,y);},250);"+
    "setTimeout(function(){try{sessionStorage.removeItem(key);}catch(e){}},800);"+
    "}"+
    "window.__veilocityRestoreImagePosition=restore;"+
    "document.addEventListener('click',save,true);"+
    "window.addEventListener('pageshow',function(e){if(e.persisted)restore();});"+
    "window.addEventListener('popstate',restore);"+
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',restore,{once:true});else restore();"+
    "})();";
  static final String COUNTRY_SECTION_JS =
    "(function(){" +
    "if(window.__veilocitySections)return;window.__veilocitySections=true;" +
    "function boot(){" +
    "var log=document.querySelector('.chatlog'),toc=document.querySelector('.toc-block');" +
    "if(!log||!toc)return;" +
    "var links=Array.from(toc.querySelectorAll('a[href^=\"#\"]'));" +
    "var groups=Array.from(log.querySelectorAll('.chatlog__message-group'));" +
    "if(!links.length||!groups.length)return;" +
    "var sections=[];" +
    "links.forEach(function(a){var id;try{id=decodeURIComponent(a.hash.slice(1));}catch(e){id=a.hash.slice(1);}" +
    "var el=document.getElementById(id),group=el&&el.closest('.chatlog__message-group');" +
    "if(group&&sections.every(function(x){return x.group!==group;}))sections.push({id:id,group:group,label:a.textContent.trim()});});" +
    "if(!sections.length)return;" +
    "var sectionNodes=sections.map(function(x){return x.group;});" +
    "var chunks=sections.map(function(x,i){var start=groups.indexOf(x.group),end=i+1<sections.length?groups.indexOf(sections[i+1].group):groups.length;return groups.slice(start,end);});" +
    "var first=groups.indexOf(sections[0].group),prefix=groups.slice(0,first);" +
    "var stash=document.createDocumentFragment();groups.forEach(function(g){stash.appendChild(g);});" +
    "var nav=document.createElement('div');nav.id='veilocity-section-nav';nav.style.cssText='display:none;padding:12px 16px;position:sticky;top:0;z-index:50;background:#f7ead9;border-bottom:1px solid #c9ad92';" +
    "var back=document.createElement('button');back.textContent='← Back to Index';back.style.cssText='padding:11px 16px;border-radius:9px;border:1px solid #bba58f;background:#fff8ed;color:#394943;font:600 15px system-ui';nav.appendChild(back);log.parentNode.insertBefore(nav,log);" +
    "var current=-1,positions={};" +
    "var sectionKey='veilocity:section-return:'+location.pathname;" +
    "function remember(){if(current<0)return;try{sessionStorage.setItem(sectionKey,JSON.stringify({index:current,y:window.scrollY,at:Date.now()}));}catch(e){}}" +
    "document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[href]');if(!a||current<0)return;var href=a.getAttribute('href')||'';if(/\\.(?:png|jpe?g|webp|gif|bmp|avif)(?:[?#]|$)/i.test(href)||/\\/_image_view\\.html/i.test(href))remember();},true);" +
    "function index(){try{sessionStorage.removeItem(sectionKey);}catch(e){}if(current>=0)positions[current]=window.scrollY;current=-1;log.replaceChildren();nav.style.display='none';toc.style.display='';window.scrollTo({top:0,behavior:'instant'});history.replaceState({veilocityIndex:true},'',location.pathname+location.search);}" +
    "function open(i,restore){if(i<0||i>=chunks.length)return;if(current>=0)positions[current]=window.scrollY;current=i;log.replaceChildren();chunks[i].forEach(function(g){log.appendChild(g);});nav.style.display='block';toc.style.display='none';window.scrollTo({top:restore?(positions[i]||0):0,behavior:'instant'});}" +
    "back.addEventListener('click',index);" +
    "toc.addEventListener('click',function(e){var a=e.target.closest('a[href^=\"#\"]');if(!a)return;var id;try{id=decodeURIComponent(a.hash.slice(1));}catch(err){id=a.hash.slice(1);}var i=sections.findIndex(function(x){return x.id===id;});if(i<0)return;e.preventDefault();e.stopImmediatePropagation();open(i,false);},true);" +
    "window.addEventListener('pageshow',function(e){if(current>=0&&e.persisted)window.scrollTo(0,positions[current]||0);});" +
    "try{var saved=JSON.parse(sessionStorage.getItem(sectionKey)||'null');var imageReturn=sessionStorage.getItem('veilocity:image-return:'+location.pathname);if(saved&&imageReturn&&Date.now()-saved.at<1800000&&saved.index>=0&&saved.index<chunks.length){open(saved.index,false);var sy=saved.y||0;[0,100,350,700].forEach(function(ms){setTimeout(function(){window.scrollTo(0,sy);},ms);});sessionStorage.removeItem(sectionKey);}}catch(e){}" +
    "// Existing Android image viewer Back retains the live DOM and active section." +
    "window.__veilocitySectionBack=index;" +
    "}" +
    "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',function(){setTimeout(boot,0);});else setTimeout(boot,0);" +
    "})();";
  String injectLibraryButton(String html){
    String injection="<script>"+CONTACT_BUTTON_JS+"</script><script>"+LIBRARY_BUTTON_JS+"</script><script>"+COURT_REDIRECT_JS+"</script><script>"+HIDE_CAPTION_BUTTONS_JS+"</script>";
    java.util.regex.Matcher head=java.util.regex.Pattern.compile("(?i)<head\\b[^>]*>").matcher(html);
    if(head.find())return html.substring(0,head.end())+injection+html.substring(head.end());
    return injection+html;
  }
  // Replace only exported archive attachment pictures before WebView sees the HTML.
  // This prevents WebView from eagerly fetching and decoding large PNG/JPEG files.
  String deferArchiveImages(String html,String documentPath){
    java.util.regex.Pattern tag=java.util.regex.Pattern.compile("(?is)<img\\b[^>]*>");
    java.util.regex.Pattern srcPattern=java.util.regex.Pattern.compile("(?i)\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    java.util.regex.Pattern classPattern=java.util.regex.Pattern.compile("(?i)\\bclass\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))");
    java.util.regex.Matcher m=tag.matcher(html);StringBuffer result=new StringBuffer();
    String base=documentPath.contains("/")?documentPath.substring(0,documentPath.lastIndexOf('/')+1):"";
    while(m.find()){
      String original=m.group();java.util.regex.Matcher cl=classPattern.matcher(original);
      String cls=cl.find()?firstNonNull(cl.group(1),cl.group(2),cl.group(3)):"";
      if(!cls.matches("(?is).*(?:chatlog__attachment-media|chatlog__embed-image|chatlog__embed-generic-image).*"))continue;
      java.util.regex.Matcher src=srcPattern.matcher(original);if(!src.find())continue;
      String path=firstNonNull(src.group(1),src.group(2),src.group(3));
      if(path==null||path.startsWith("data:")||path.startsWith("http:")||path.startsWith("https:")||path.startsWith("//"))continue;
      String clean=path.split("[?#]",2)[0];
      if(!clean.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp|avif)$"))continue;
      // Archive exports use relative media paths; refuse traversal and external URLs.
      if(clean.startsWith("/")||clean.contains("..")||clean.contains("\\"))continue;
      // HTML src is URL-encoded (e.g. ENGLISH%20-%20Court.html_Files).
      // SAF document names contain actual spaces, not the literal "%20".
      // Decode once before constructing the viewer query; encodePath() will
      // re-encode each filesystem segment when the viewer requests the image.
      String decodedClean;
      try{
        StringBuilder pathParts=new StringBuilder();
        for(String segment:clean.split("/")){
          String decodedSegment=URLDecoder.decode(segment.replace("+","%2B"),"UTF-8");
          if(decodedSegment.equals(".")||decodedSegment.equals("..")||decodedSegment.contains("/")||decodedSegment.contains("\\")||decodedSegment.indexOf('\0')>=0){pathParts.setLength(0);break;}
          if(pathParts.length()>0)pathParts.append('/');
          pathParts.append(decodedSegment);
        }
        decodedClean=pathParts.toString();
      }catch(Exception badPath){continue;}
      if(decodedClean.isEmpty())continue;
      String target=base+decodedClean;
      // Show the original image inline, but open the existing zoomable image viewer on tap.
      // Preserve the source image's CSS classes, sizing and alt text.
      String inlineImage=original;
      if(!java.util.regex.Pattern.compile("(?i)\\bloading\\s*=").matcher(inlineImage).find())
        inlineImage=inlineImage.replaceFirst("(?i)<img\\b", "<img loading='lazy'");
      String link="<a class='veilocity-image-attachment' href='/files/_image_view.html?src="+encode(target)+"' aria-label='Open image in detailed viewer' style='display:inline-block;max-width:100%;text-decoration:none;cursor:zoom-in'>"+
        inlineImage+"</a>";
      m.appendReplacement(result,java.util.regex.Matcher.quoteReplacement(link));
    }
    m.appendTail(result);return result.toString();
  }
  // Country images: resolve the actual displayed file path at tap time, rather
  // than rewriting markup (which can create nested anchors in exported HTML).
  // All local archive images, including images inside existing attachment links,
  // use the same full-featured viewer as Court. Capture in the document phase so
  // legacy image anchors cannot navigate directly to the raw image.
  static final String INLINE_IMAGE_OVERLAY_JS =
    "(function(){\n" +
    "'use strict';\n" +
    "if(window.__veilocityImageOverlayInstalled)return;\n" +
    "window.__veilocityImageOverlayInstalled=true;\n" +
    "var host=null,modal=null,image=null,scale=1,tx=0,ty=0,active=new Map(),pinchDistance=0,pinchScale=1,pinchX=0,pinchY=0,dragX=0,dragY=0,dragTX=0,dragTY=0;\n" +
    "function close(fromHistory){if(!modal)return;host.remove();host=null;modal=null;image=null;active.clear();document.removeEventListener('keydown',onKey,true);window.removeEventListener('resize',sizeOverlay);if(window.visualViewport){visualViewport.removeEventListener('resize',sizeOverlay);visualViewport.removeEventListener('scroll',sizeOverlay);}if(!fromHistory&&history.state&&history.state.veilocityImageOverlay)history.back();}\n" +
    "function onKey(e){if(e.key==='Escape'&&modal){e.preventDefault();close(false);}}\n" +
    "function resolve(a){try{var u=new URL(a,location.href);if(u.origin!==location.origin||!u.pathname.startsWith('/files/'))return null;if(u.pathname.endsWith('/_image_view.html')){var rel=u.searchParams.get('src');if(!rel)return null;u=new URL('/files/'+rel.replace(/^\\/+/,''),location.origin);}if(!/\\.(png|jpe?g|gif|webp|bmp|avif)$/i.test(u.pathname))return null;return u.href;}catch(e){return null;}}\n" +
    "function open(url){\n" +
    " if(modal)close(true);\n" +
    " scale=1;tx=0;ty=0;active.clear();\n" +
    " host=document.createElement('div');host.id='veilocity-photo-overlay-host';host.style.cssText='position:fixed!important;inset:0!important;z-index:2147483647!important;overflow:hidden!important;';var shadow=host.attachShadow({mode:'open'});\n" +
    " modal=document.createElement('div');modal.id='veilocity-photo-overlay';\n" +
    " modal.style.cssText='position:absolute;inset:0;width:100%;height:100%;box-sizing:border-box;background:#080a0d;display:flex;flex-direction:column;overflow:hidden;';\n" +
    " function sizeOverlay(){if(!modal)return;var v=window.visualViewport;var w=v?v.width:document.documentElement.clientWidth;var h=v?v.height:window.innerHeight;var left=v?v.offsetLeft:0;var top=v?v.offsetTop:0;host.style.setProperty('left',left+'px','important');host.style.setProperty('top',top+'px','important');host.style.setProperty('width',Math.max(1,w)+'px','important');host.style.setProperty('height',Math.max(1,h)+'px','important');}\n" +
    " sizeOverlay();\n" +
    " var bar=document.createElement('div');bar.style.cssText='position:relative;z-index:3;box-sizing:border-box;width:100%;min-width:0;flex:0 0 auto;min-height:60px;display:flex;align-items:center;gap:5px;padding:8px max(36px, env(safe-area-inset-right, 0px)) 8px max(20px, env(safe-area-inset-left, 0px));color:white;background:#171c23;font:14px sans-serif;overflow:hidden;';\n" +
    " var label=document.createElement('span');label.style.cssText='overflow:hidden;text-overflow:ellipsis;white-space:nowrap;flex:1;min-width:0;';try{label.textContent=decodeURIComponent(new URL(url).pathname.split('/').pop());}catch(e){label.textContent='Image';}\n" +
    " var fit=document.createElement('button');fit.textContent='Fit';var minus=document.createElement('button');minus.textContent='−';var plus=document.createElement('button');plus.textContent='+';var x=document.createElement('button');x.textContent='✕';x.setAttribute('aria-label','Close image');\n" +
    " [fit,minus,plus,x].forEach(function(btn){btn.style.cssText='box-sizing:border-box;background:#303943;color:white;border:1px solid #68737e;border-radius:8px;min-width:34px;width:36px;height:40px;padding:0;font-size:17px;cursor:pointer;flex-shrink:0;';});\n" +
    " bar.append(label,minus,fit,plus,x);\n" +
    " var viewport=document.createElement('div');viewport.style.cssText='position:relative;flex:1;min-height:0;overflow:hidden;touch-action:none;overscroll-behavior:contain;user-select:none;';\n" +
    " image=document.createElement('img');image.alt=label.textContent;image.decoding='async';image.style.cssText='position:absolute;left:0;top:0;width:100%;height:100%;max-width:none;max-height:none;object-fit:contain;transform-origin:center center;user-select:none;-webkit-user-drag:none;will-change:transform;pointer-events:none;';\n" +
    " viewport.appendChild(image);modal.append(bar,viewport);shadow.appendChild(modal);document.body.appendChild(host);\n" +
    " function draw(){if(image)image.style.transform='translate3d('+tx+'px,'+ty+'px,0) scale('+scale+')';}\n" +
    " function zoom(next,cx,cy){var old=scale;scale=Math.max(.5,Math.min(8,next));var rect=viewport.getBoundingClientRect();var px=(cx===undefined?rect.width/2:cx)-rect.width/2;var py=(cy===undefined?rect.height/2:cy)-rect.height/2;var r=scale/old;tx=px-(px-tx)*r;ty=py-(py-ty)*r;draw();}\n" +
    " fit.onclick=function(){scale=1;tx=0;ty=0;draw();};plus.onclick=function(){zoom(scale*1.35);};minus.onclick=function(){zoom(scale/1.35);};x.onclick=function(){close(false);};\n" +
    " viewport.addEventListener('dblclick',function(e){var r=viewport.getBoundingClientRect();zoom(scale===1?2:1,e.clientX-r.left,e.clientY-r.top);});\n" +
    " viewport.addEventListener('pointerdown',function(e){if(e.pointerType==='mouse'&&e.button!==0)return;e.preventDefault();viewport.setPointerCapture(e.pointerId);active.set(e.pointerId,{x:e.clientX,y:e.clientY});if(active.size===1){dragX=e.clientX;dragY=e.clientY;dragTX=tx;dragTY=ty;}else if(active.size===2){var p=[...active.values()];pinchDistance=Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y);pinchScale=scale;pinchX=tx;pinchY=ty;}});\n" +
    " viewport.addEventListener('pointermove',function(e){if(!active.has(e.pointerId))return;e.preventDefault();active.set(e.pointerId,{x:e.clientX,y:e.clientY});if(active.size===1){tx=dragTX+e.clientX-dragX;ty=dragTY+e.clientY-dragY;draw();}else if(active.size===2){var p=[...active.values()],d=Math.hypot(p[0].x-p[1].x,p[0].y-p[1].y);if(pinchDistance){var next=Math.max(.5,Math.min(8,pinchScale*d/pinchDistance));scale=next;tx=pinchX;ty=pinchY;draw();}}});\n" +
    " function release(e){active.delete(e.pointerId);if(active.size===1){var p=[...active.values()][0];dragX=p.x;dragY=p.y;dragTX=tx;dragTY=ty;}else if(!active.size)pinchDistance=0;}\n" +
    " viewport.addEventListener('pointerup',release);viewport.addEventListener('pointercancel',release);\n" +
    " viewport.addEventListener('wheel',function(e){e.preventDefault();var r=viewport.getBoundingClientRect();zoom(scale*(e.deltaY<0?1.15:1/1.15),e.clientX-r.left,e.clientY-r.top);},{passive:false});\n" +
    " window.addEventListener('resize',sizeOverlay);if(window.visualViewport){visualViewport.addEventListener('resize',sizeOverlay);visualViewport.addEventListener('scroll',sizeOverlay);}\n" +
    " draw();var previewUrl=new URL(url);previewUrl.searchParams.set('veilocity_preview','1');image.src=previewUrl.href;\n" +
    " image.onload=function(){if(image&&image.src===previewUrl.href){var fullUrl=new URL(url);fullUrl.searchParams.set('veilocity_full','1');var full=new Image();full.decoding='async';full.onload=function(){if(image&&modal){image.src=fullUrl.href;draw();}};full.src=fullUrl.href;}};\n" +
    " document.addEventListener('keydown',onKey,true);\n" +
    " history.pushState({veilocityImageOverlay:true},'',location.href);\n" +
    "}\n" +
    "window.__veilocityOpenImage=function(url){var resolved=resolve(url);if(resolved){open(resolved);return true;}return false;};\n" +
    "window.addEventListener('popstate',function(){if(modal)close(true);});\n" +
    "document.addEventListener('click',function(e){\n" +
    " if(modal)return;\n" +
    " var a=e.target.closest&&e.target.closest('a[href]');var img=e.target.closest&&e.target.closest('img');\n" +
    " if(a&&/\\.html?(?:[?#]|$)/i.test(a.getAttribute('href')||'')&&!/\\/_image_view\\.html/i.test(a.getAttribute('href')||''))return;\n" +
    " var url=(a&&resolve(a.href))||(img&&resolve(img.currentSrc||img.src));\n" +
    " if(!url)return;\n" +
    " e.preventDefault();e.stopPropagation();e.stopImmediatePropagation();open(url);\n" +
    "},true);\n" +
    "})();";
  String escapeHtml(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
  String firstNonNull(String... values){for(String v:values)if(v!=null)return v;return "";}

  // Rewrite archive image links before WebView loads the page. Unlike a click
  // listener, this also works when exported HTML handles clicks itself.
  // Only country index pages are processed; landing-page flag links remain HTML links.
  String countryViewerLinks(String html,String documentPath){
    java.util.regex.Pattern anchor=java.util.regex.Pattern.compile("(?is)<a\\b([^>]*?)\\bhref\\s*=\\s*([\"'])(.*?)\\2([^>]*)>(.*?)</a>");
    java.util.regex.Pattern image=java.util.regex.Pattern.compile("(?is)<img\\b");
    java.util.regex.Matcher m=anchor.matcher(html);
    StringBuffer out=new StringBuffer();
    String base=documentPath.contains("/")?documentPath.substring(0,documentPath.lastIndexOf('/')+1):"";
    while(m.find()){
      String href=m.group(3);
      String clean=href.split("[?#]",2)[0];
      if(!image.matcher(m.group(5)).find()||!clean.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp|avif)")||
         clean.startsWith("/")||clean.contains("..")||clean.contains("\\")||clean.contains(":")){
        m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(m.group()));continue;
      }
      StringBuilder decoded=new StringBuilder();boolean valid=true;
      try{
        for(String seg:clean.split("/")){
          String part=URLDecoder.decode(seg.replace("+","%2B"),"UTF-8");
          if(part.isEmpty()||part.equals(".")||part.equals("..")||part.contains("/")||part.contains("\\")){valid=false;break;}
          if(decoded.length()>0)decoded.append('/');
          decoded.append(part);
        }
      }catch(Exception e){valid=false;}
      if(!valid){m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(m.group()));continue;}
      String viewer="/files/_image_view.html?src="+encode(base+decoded.toString());
      String replacement="<a"+m.group(1)+"href="+m.group(2)+viewer+m.group(2)+m.group(4)+">"+m.group(5)+"</a>";
      m.appendReplacement(out,java.util.regex.Matcher.quoteReplacement(replacement));
    }
    m.appendTail(out);return out.toString();
  }
  byte[] htmlWithClickToLoad(Uri uri,String documentPath) throws Exception {
    String html=read(uri);
    // Use the same inline-image and detailed viewer handling for Court and country indexes.
    String normalizedPath=documentPath.replace('\\','/').toLowerCase(Locale.ROOT);
    boolean isCountryIndex=normalizedPath.matches(".+/index\\.html") && !normalizedPath.startsWith("1_library/");
    if(normalizedPath.startsWith("court-of-illusions-trafficking/") || normalizedPath.contains("/court-of-illusions-trafficking/"))
      html=deferArchiveImages(html,documentPath);
    String injection="<script>"+CLICK_TO_LOAD_JS+"</script><script>"+CONTACT_BUTTON_JS+"</script><script>"+LIBRARY_BUTTON_JS+"</script><script>"+COURT_REDIRECT_JS+"</script>";
    injection+="<script>"+HIDE_CAPTION_BUTTONS_JS+"</script>";
    if(normalizedPath.equals("index.html"))injection+="<script>"+DOWNLOADS_BUTTON_JS+"</script>";
    if(normalizedPath.equals("index.html"))injection+="<script>"+EMPTY_ARCHIVE_NOTICE_JS+"</script>";
    // Country indexes only: retain the precise section/scroll location after image viewing.
    // Overlay keeps the original DOM alive; no link rewriting or page reconstruction.
    injection+="<script>"+INLINE_IMAGE_OVERLAY_JS+"</script>";
    java.util.regex.Matcher head=java.util.regex.Pattern.compile("(?i)<head\\b[^>]*>").matcher(html);
    if(head.find())html=html.substring(0,head.end())+injection+html.substring(head.end());
    else html=injection+html;
    return html.getBytes(StandardCharsets.UTF_8);
  }
  void serve(Socket c){try{c.setSoTimeout(15000);BufferedReader r=new BufferedReader(new InputStreamReader(c.getInputStream(),StandardCharsets.ISO_8859_1));String request=r.readLine();if(request==null)return;String[] parts=request.split(" ");if(parts.length<2)return;String line;String rangeHeader=null;while((line=r.readLine())!=null&&!line.isEmpty()){if(line.regionMatches(true,0,"Range:",0,6))rangeHeader=line.substring(6).trim();}OutputStream out=c.getOutputStream();if(!parts[0].equals("GET")&&!parts[0].equals("HEAD")){send(out,405,"text/plain","Method not allowed".getBytes(StandardCharsets.UTF_8));return;}
      String path=parts[1].split("\\?",2)[0];if(path.equals("/")){send(out,200,"text/html",("<script>"+INLINE_IMAGE_OVERLAY_JS+"</script>"+homeHtml()).getBytes(StandardCharsets.UTF_8));return;}
      if(!path.startsWith("/files/")){send(out,404,"text/plain",new byte[0]);return;}
      String raw=path.substring(7);StringBuilder decoded=new StringBuilder();for(String seg:raw.split("/")){String s=URLDecoder.decode(seg.replace("+","%2B"),"UTF-8");if(s.equals("..")||s.contains("/")||s.contains("\\")||s.indexOf('\0')>=0){send(out,403,"text/plain",new byte[0]);return;}if(decoded.length()>0)decoded.append('/');decoded.append(s);}
      String requested=decoded.toString();
      if(requested.equals("downloads.html")||requested.equals("veilocity-downloads.html")){
        try(InputStream in=getAssets().open("veilocity-downloads.html")){
          ByteArrayOutputStream bytes=new ByteArrayOutputStream();
          byte[] buffer=new byte[8192];int n;
          while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
          send(out,200,"text/html; charset=utf-8",parts[0].equals("HEAD")?new byte[0]:bytes.toByteArray());
        }
        return;
      }
      if(requested.equals("_image_view.html")){
        String query=parts[1].contains("?")?parts[1].substring(parts[1].indexOf('?')+1):"";
        String relative="";
        for(String part:query.split("&"))if(part.startsWith("src=")){
          relative=URLDecoder.decode(part.substring(4).replace("+","%2B"),"UTF-8");break;
        }
        boolean valid=!relative.isEmpty()&&!relative.startsWith("/")&&!relative.contains("\\")&&!relative.contains("\0");
        for(String segment:relative.split("/"))if(segment.equals("..")||segment.equals("."))valid=false;
        if(!relative.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp|avif)$"))valid=false;
        Uri picture=valid?resolve(relative):null;
        if(picture==null||isDirectory(picture)){send(out,404,"text/plain",new byte[0]);return;}
        send(out,200,"text/html; charset=utf-8",imageViewerHtml(relative,false).getBytes(StandardCharsets.UTF_8));return;
      }
      if(requested.equals("1_Library/_image_view.html")){
        String query=parts[1].contains("?")?parts[1].substring(parts[1].indexOf('?')+1):"";
        String relative="";
        for(String part:query.split("&"))if(part.startsWith("src=")){
          relative=URLDecoder.decode(part.substring(4).replace("+","%2B"),"UTF-8");break;
        }
        boolean valid=!relative.isEmpty()&&!relative.startsWith("/")&&!relative.contains("\\")&&!relative.contains("\0");
        for(String segment:relative.split("/"))if(segment.equals("..")||segment.equals("."))valid=false;
        if(!relative.toLowerCase(Locale.ROOT).matches(".*\\.(png|jpe?g|gif|webp|bmp|avif)$"))valid=false;
        Uri image=valid?resolve("1_Library/"+relative):null;
        if(image==null||isDirectory(image)){send(out,404,"text/plain",new byte[0]);return;}
        send(out,200,"text/html; charset=utf-8",imageViewerHtml(relative).getBytes(StandardCharsets.UTF_8));return;
      }
      if(requested.equals("1_Library/index.html")||requested.equals("1_Library/_browse.html")||
          (requested.startsWith("1_Library/")&&requested.endsWith("/_browse.html"))){
        String relative="";
        if(!requested.equals("1_Library/index.html")&&!requested.equals("1_Library/_browse.html"))
          relative=requested.substring("1_Library/".length(),requested.length()-"/_browse.html".length());
        send(out,200,"text/html; charset=utf-8",("<script>"+INLINE_IMAGE_OVERLAY_JS+"</script>"+libraryHtml(relative)).getBytes(StandardCharsets.UTF_8));return;
      }
      // The starter homepage and artwork are served from APK assets before SAF
      // selection; existing selected-folder files still take precedence.
      if(requested.equals("logo.webp")||requested.equals("logo2.webp") ||
          requested.equals("logo.png")||requested.equals("logo2.png")){
        Uri override=resolve(requested);
        if(override==null){
          String asset=requested.equals("logo.webp")?"starter-logo.webp":
              requested.equals("logo2.webp")?"starter-logo2.webp":
              requested.equals("logo.png")?"veilocity-logo.png":"veilocity-logo2.png";
          try(InputStream in=getAssets().open(asset)){
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buf=new byte[16384];int n;
            while((n=in.read(buf))!=-1)bytes.write(buf,0,n);
            send(out,200,requested.endsWith(".webp")?"image/webp":"image/png",
                parts[0].equals("HEAD")?new byte[0]:bytes.toByteArray());
          }
          return;
        }
      }
      // Bundled landing-page artwork: keep optional user-supplied overrides,
      // but do not require logo.png or logo2.png in the archive directory.
      if(requested.equals("logo.png")||requested.equals("logo2.png")){
        Uri custom=resolve(requested);
        if(custom==null){
          String assetName=requested.equals("logo.png")?"veilocity-logo.png":"veilocity-logo2.png";
          try(InputStream artwork=getAssets().open(assetName)){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[16384];int n;
            while((n=artwork.read(buffer))!=-1)bytes.write(buffer,0,n);
            send(out,200,"image/png",parts[0].equals("HEAD")?new byte[0]:bytes.toByteArray());
          }
          return;
        }
      }
      // Native Android replacement for the PC manifest builder:
      // calculate current archive listings directly from the selected folder.
      // Serve these before checking disk so outdated PC-generated JS never wins.
      if(requested.equals("archives.js")){
        send(out,200,"text/javascript; charset=utf-8",virtualArchives().getBytes(StandardCharsets.UTF_8));return;
      }
      if(requested.endsWith("/manifest.js")){
        String folder=requested.substring(0,requested.length()-"/manifest.js".length());
        if(archiveFolders().contains(folder)){
          send(out,200,"text/javascript; charset=utf-8",virtualManifest(folder).getBytes(StandardCharsets.UTF_8));return;
        }
      }
      // The Court button always has a useful destination, even without SD-card setup.
      // Only the missing exact Court HTML is replaced; real Court files remain untouched.
      if(requested.equalsIgnoreCase("court-of-illusions-trafficking/ENGLISH - Court.html") &&
          resolve(requested)==null){
        send(out,200,"text/html; charset=utf-8",parts[0].equals("HEAD")?new byte[0]:courtMissingHtml().getBytes(StandardCharsets.UTF_8));return;
      }
      // Preserve the user's existing landing page and HTML unchanged.
      // If index.html is missing, serve the bundled Veilocity landing page.
      Uri u=resolve(decoded.toString());
      if(u==null){
        if(decoded.toString().equals("index.html")){send(out,200,"text/html; charset=utf-8",bundledLanding());return;}
        if(decoded.toString().equals("archives.js")){send(out,200,"text/javascript",virtualArchives().getBytes(StandardCharsets.UTF_8));return;}
        if(decoded.toString().endsWith("/manifest.js")){
          String folder=decoded.substring(0,decoded.length()-"/manifest.js".length());
          if(archiveFolders().contains(folder)){send(out,200,"text/javascript",virtualManifest(folder).getBytes(StandardCharsets.UTF_8));return;}
        }
        send(out,404,"text/plain",new byte[0]);return;
      }
      // Offline read-only Word / Outlook previews. No changes to media or PDF handling.
      String lowerOffice=requested.toLowerCase(Locale.ROOT);
      if(lowerOffice.endsWith(".docx")||lowerOffice.endsWith(".doc")||lowerOffice.endsWith(".msg")){
        try(InputStream office=getContentResolver().openInputStream(u)){
          if(office==null)throw new IOException("Cannot read document");
          String name=requested.substring(requested.lastIndexOf('/')+1);
          String html=lowerOffice.endsWith(".docx")?OfficeViewer.docx(office,name):lowerOffice.endsWith(".msg")?OfficeViewer.msg(office,name):OfficeViewer.doc(office,name);
          send(out,200,"text/html; charset=utf-8",parts[0].equals("HEAD")?new byte[0]:html.getBytes(StandardCharsets.UTF_8));
        }catch(Exception e){diagnostic("Office preview failed",e);
          String html=OfficeViewer.shell(requested,"document","<h2>Unable to preview document</h2><p>"+OfficeViewer.esc(e.getMessage())+"</p><p>The original file has not been modified.</p>");
          send(out,200,"text/html; charset=utf-8",html.getBytes(StandardCharsets.UTF_8));
        }return;
      }
      // Offline XLSX preview: render in a separate read-only HTML view. Never alter source.
      if(requested.toLowerCase(Locale.ROOT).endsWith(".xlsx")){
        try(InputStream xlsx=getContentResolver().openInputStream(u)){
          if(xlsx==null)throw new IOException("Cannot read spreadsheet");
          String name=requested.substring(requested.lastIndexOf('/')+1);
          byte[] page=XlsxViewer.page(xlsx,name).getBytes(StandardCharsets.UTF_8);
          send(out,200,"text/html; charset=utf-8",parts[0].equals("HEAD")?new byte[0]:page);
        }catch(Exception e){diagnostic("XLSX preview failed",e);
          String msg="<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='background:#f8ead8;color:#51473c;font:16px system-ui;padding:22px'><h2>Unable to preview spreadsheet</h2><p>"+XlsxViewer.esc(e.getMessage())+"</p><p>The original file has not been modified.</p></body></html>";
          send(out,200,"text/html; charset=utf-8",msg.getBytes(StandardCharsets.UTF_8));
        }
        return;
      }
      // Serve prepared previews for inline Court and country archive images by default. The overlay explicitly
      // requests originals with veilocity_full=1, preserving full-resolution zoom.
      boolean wantsOriginal=parts[1].contains("veilocity_full=1");
      boolean wantsPreview=parts[1].contains("veilocity_preview=1") || !wantsOriginal;
      if(wantsPreview&&courtPhoto(requested)&&rangeHeader==null){
        File preview=previewFile(requested);
        if(preview.isFile()&&preview.length()>0){preview.setLastModified(System.currentTimeMillis());streamCachedPhoto(out,preview,"image/jpeg",parts[0].equals("HEAD"));return;}
        final Uri photo=u;final String photoPath=requested;final int generation=photoCacheGeneration;
        if(pool!=null)pool.submit(()->cachePreview(photoPath,photo,generation));
        // On first request, fall back immediately to the original rather than waiting for conversion.
      }
      // Stream files directly; MP4 seeking needs HTTP 206 byte-range responses.
      String fileMime=mime(decoded.toString());
      if(fileMime.equals("text/html")){
        byte[] html=htmlWithClickToLoad(u,requested);
        send(out,200,"text/html; charset=utf-8",parts[0].equals("HEAD")?new byte[0]:html);
      } else {
        if(courtPhoto(requested)&&rangeHeader==null){
          File cached=new File(photoCacheDir(),photoCacheKey(requested));
          if(cached.isFile()&&cached.length()>0){cached.setLastModified(System.currentTimeMillis());streamCachedPhoto(out,cached,fileMime,parts[0].equals("HEAD"));return;}
          final Uri original=u;final String photoPath=requested;final int generation=photoCacheGeneration;
          // Only one worker per image; never block the HTTP request on cache creation.
          if(pool!=null)pool.submit(()->cachePhoto(photoPath,original,generation));
        }
        streamFile(out,u,fileMime,rangeHeader,parts[0].equals("HEAD"));
      }
    }catch(Exception ignored){}finally{try{c.close();}catch(IOException ignored){}}}
  void streamCachedPhoto(OutputStream out,File file,String type,boolean head)throws IOException{
    String hdr="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nContent-Length: "+file.length()+"\r\nCache-Control: private, max-age=86400\r\nConnection: close\r\n\r\n";
    out.write(hdr.getBytes(StandardCharsets.US_ASCII));
    if(!head)try(InputStream in=new FileInputStream(file)){copyBytes(in,out,-1);}
    out.flush();
  }
  long documentSize(Uri uri){
    try(Cursor cur=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){
      if(cur!=null&&cur.moveToFirst()&&!cur.isNull(0))return cur.getLong(0);
    }catch(Exception ignored){}
    try(android.content.res.AssetFileDescriptor afd=getContentResolver().openAssetFileDescriptor(uri,"r")){
      if(afd!=null&&afd.getLength()>=0)return afd.getLength();
    }catch(Exception ignored){}
    return -1;
  }
  void skipFully(InputStream in,long amount)throws IOException{
    while(amount>0){long n=in.skip(amount);if(n<=0){if(in.read()==-1)throw new EOFException("Unexpected end of file");n=1;}amount-=n;}
  }
  void streamFile(OutputStream out,Uri uri,String type,String range,boolean head)throws IOException{
    long length=documentSize(uri);
    if(length<0){
      // Unknown-size provider: stream without Content-Length; seeking cannot be guaranteed.
      String hdr="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nConnection: close\r\n\r\n";
      out.write(hdr.getBytes(StandardCharsets.US_ASCII));
      if(!head)try(InputStream in=getContentResolver().openInputStream(uri)){if(in!=null)copyBytes(in,out,-1);}
      out.flush();return;
    }
    long start=0,end=length-1;boolean partial=false;
    if(range!=null&&range.startsWith("bytes=")){
      String spec=range.substring(6).trim();int dash=spec.indexOf('-');
      if(dash>=0&&!spec.contains(",")){
        try{
          String left=spec.substring(0,dash).trim(),right=spec.substring(dash+1).trim();
          if(left.isEmpty()){
            long suffix=Long.parseLong(right);if(suffix<=0)throw new NumberFormatException();
            start=Math.max(0,length-suffix);
          }else{
            start=Long.parseLong(left);
            if(!right.isEmpty())end=Math.min(end,Long.parseLong(right));
          }
          if(start<0||start>=length||end<start)throw new NumberFormatException();
          partial=true;
        }catch(NumberFormatException ex){
          String hdr="HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */"+length+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
          out.write(hdr.getBytes(StandardCharsets.US_ASCII));out.flush();return;
        }
      }
    }
    long count=partial?end-start+1:length;
    String hdr="HTTP/1.1 "+(partial?"206 Partial Content":"200 OK")+"\r\n"+
      "Content-Type: "+type+"\r\nContent-Length: "+count+"\r\n"+
      "Accept-Ranges: bytes\r\n"+
      (partial?"Content-Range: bytes "+start+"-"+end+"/"+length+"\r\n":"")+
      "Access-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";
    out.write(hdr.getBytes(StandardCharsets.US_ASCII));
    if(!head&&count>0)try(InputStream in=getContentResolver().openInputStream(uri)){
      if(in==null)throw new FileNotFoundException(uri.toString());
      skipFully(in,start);copyBytes(in,out,count);
    }
    out.flush();
  }
  void copyBytes(InputStream in,OutputStream out,long remaining)throws IOException{
    byte[] buf=new byte[65536];int n;
    while(remaining!=0&&(n=in.read(buf,0,remaining<0?buf.length:(int)Math.min(buf.length,remaining)))!=-1){
      out.write(buf,0,n);if(remaining>0)remaining-=n;
    }
  }
  void send(OutputStream out,int status,String type,byte[] body)throws IOException{String hdr="HTTP/1.1 "+status+" OK\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nAccess-Control-Allow-Origin: *\r\nConnection: close\r\n\r\n";out.write(hdr.getBytes(StandardCharsets.US_ASCII));out.write(body);out.flush();}
  // Pause any playing HTML5 video before leaving the current document.
  // The JavaScript callback executes before WebView history navigation.
  void navigateBackAndStopVideo(){
    if(pdfVisible){closePdf();return;}
    if(!web.canGoBack())return;
    web.evaluateJavascript("(function(){document.querySelectorAll('video').forEach(function(v){try{v.pause();}catch(e){}});return true;})()", result -> web.goBack());
  }
  @Override public void onBackPressed(){
    if(pdfVisible){closePdf();return;}
    if(web.canGoBack())navigateBackAndStopVideo();
    else {
      web.evaluateJavascript("(function(){document.querySelectorAll('video').forEach(function(v){try{v.pause();}catch(e){}});return true;})()", result -> MainActivity.super.onBackPressed());
    }
  }
  @Override public void onLowMemory(){
    super.onLowMemory();clearDirectoryCache();
    android.util.Log.w("VeilocityStability","Android low-memory callback");
  }
  @Override public void onTrimMemory(int level){
    super.onTrimMemory(level);
    if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)clearDirectoryCache();
    android.util.Log.i("VeilocityStability","Memory trim level="+level);
  }
  @Override protected void onDestroy(){clearDirectoryCache();closePdf();stopServer();web.destroy();super.onDestroy();}
}
