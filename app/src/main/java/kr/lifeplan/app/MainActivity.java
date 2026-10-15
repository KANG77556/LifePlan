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
  web.addJavascriptInterface(new Object(){@android.webkit.JavascriptInterface public void update(){runOnUiThread(()->{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/KANG77556/LifePlan/releases/latest")));});}}, "LifePlanNative");
  setContentView(web); web.loadUrl("file:///android_asset/index.html");
 }
 @Override public void onBackPressed(){if(web.canGoBack())web.goBack();else super.onBackPressed();}
}