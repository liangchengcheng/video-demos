package com.vanco.abplayer;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

/**
 * Created by lcc on 16/3/15.
 */
public class Start extends Activity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.start);
        findViewById(R.id.button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent =new Intent(Start.this,MainActivity.class);
                intent.setData(Uri.parse("http://mvvideo2.meitudata.com/5633c440f3dd33829.mp4"));
                //intent.putExtra("path", item.url);
                intent.putExtra("displayName","hhehe");
                startActivity(intent);
                startActivity(new Intent(Start.this,MainActivity.class));
            }
        });
    }
}
