package io.airtap.preview;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Receives a real Android URI grant from the synthetic provider, then calls production FileScopes. */
public final class NativeFolderActivity extends Activity {
    private String claim;
    @Override public void onCreate(Bundle state){super.onCreate(state);claim=getIntent().getStringExtra("claim");
        startActivityForResult(new Intent().setClassName("io.airtap.fixture.multitarget","test.multi.GrantActivity"),12);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        try{if(result!=RESULT_OK||data==null)throw new IllegalStateException("grant_missing");
            FileScopes.grant(this,claim,data.getData(),data.getFlags());
            try(java.io.FileOutputStream out=openFileOutput("native-folder.json",MODE_PRIVATE)){out.write("{\"ok\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }catch(Exception e){try(java.io.FileOutputStream out=openFileOutput("native-folder.json",MODE_PRIVATE)){out.write(("{\"ok\":false,\"error\":\""+e.getClass().getSimpleName()+"\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8));}catch(Exception ignored){}}
        finish();
    }
}
