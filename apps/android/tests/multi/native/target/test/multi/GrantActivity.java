package test.multi;
import android.app.Activity;import android.content.Intent;import android.os.Bundle;import android.provider.DocumentsContract;
public final class GrantActivity extends Activity {
    @Override public void onCreate(Bundle state){super.onCreate(state);
        Intent data=new Intent().setData(DocumentsContract.buildTreeDocumentUri("io.airtap.fixture.multitarget.documents","root"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        setResult(RESULT_OK,data);finish();
    }
}
