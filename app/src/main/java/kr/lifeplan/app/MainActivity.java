package kr.lifeplan.app;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebChromeClient;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;
public class MainActivity extends Activity {
 WebView web;
 @Override public void onCreate(Bundle b){super.onCreate(b);
  web=new WebView(this); WebSettings s=web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDefaultTextEncodingName("UTF-8");
  web.setWebViewClient(new WebViewClient(){@Override public boolean shouldOverrideUrlLoading(WebView view,String url){if(url.startsWith("https://")){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));return true;}return false;}});
  web.setWebChromeClient(new WebChromeClient());
  web.addJavascriptInterface(new Object(){@android.webkit.JavascriptInterface public void openInstaller(String url){if(!url.startsWith("https://github.com/KANG77556/LifePlan/releases/download/"))return;runOnUiThread(()->{try{Intent intent=new Intent(Intent.ACTION_VIEW,Uri.parse(url));startActivity(intent);}catch(Exception e){Toast.makeText(MainActivity.this,"다운로드 앱을 열 수 없습니다.",Toast.LENGTH_LONG).show();}});} }, "LifePlanNative");
  setContentView(web); web.loadUrl("file:///android_asset/index.html");
 }
 @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
}