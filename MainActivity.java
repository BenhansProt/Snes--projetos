package br.snesbuild;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_ARQUIVOS = 1;
    private static final int REQ_PASTA = 2;
    private WebView web;
    private ValueCallback<Uri[]> seletor;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);          // IndexedDB: guarda os arquivos do projeto
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);

        web.addJavascriptInterface(new Ponte(), "SnesBridge");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (seletor != null) seletor.onReceiveValue(null);
                seletor = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_ARQUIVOS);
                } catch (ActivityNotFoundException e) {
                    seletor = null;
                    return false;
                }
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");
    }

    private void js(final String codigo) {
        runOnUiThread(() -> web.evaluateJavascript(codigo, null));
    }

    /** Percorre a pasta escolhida e manda cada arquivo para a página (caminho relativo + base64). */
    private void lerPasta(final Uri arvore) {
        new Thread(() -> {
            int n = 0;
            try {
                String raiz = DocumentsContract.getTreeDocumentId(arvore);
                String nomeRaiz = raiz.contains(":") ? raiz.substring(raiz.lastIndexOf(':') + 1) : raiz;
                if (nomeRaiz.contains("/")) nomeRaiz = nomeRaiz.substring(nomeRaiz.lastIndexOf('/') + 1);
                if (nomeRaiz.isEmpty()) nomeRaiz = "DADOS";
                n = percorrer(arvore, raiz, nomeRaiz);
            } catch (Exception e) {
                aviso("Erro ao ler a pasta: " + e);
            }
            js("window.pastaNativaFim&&window.pastaNativaFim(" + n + ")");
        }).start();
    }

    private int percorrer(Uri arvore, String docId, String caminho) throws Exception {
        int n = 0;
        Uri filhos = DocumentsContract.buildChildDocumentsUriUsingTree(arvore, docId);
        java.util.ArrayList<String[]> itens = new java.util.ArrayList<>();
        try (Cursor c = getContentResolver().query(filhos, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) itens.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        for (String[] it : itens) {
            String nome = it[1];
            if (nome == null || nome.startsWith(".")) continue;
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(it[2])) {
                n += percorrer(arvore, it[0], caminho + "/" + nome);
            } else {
                Uri u = DocumentsContract.buildDocumentUriUsingTree(arvore, it[0]);
                try (InputStream in = getContentResolver().openInputStream(u)) {
                    ByteArrayOutputStream o = new ByteArrayOutputStream();
                    byte[] b = new byte[65536];
                    int k;
                    while ((k = in.read(b)) > 0) o.write(b, 0, k);
                    String b64 = Base64.encodeToString(o.toByteArray(), Base64.NO_WRAP);
                    String p = (caminho + "/" + nome).replace("\\", "\\\\").replace("'", "\\'");
                    js("window.pastaNativa&&window.pastaNativa('" + p + "','" + b64 + "')");
                    n++;
                }
            }
        }
        return n;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req == REQ_PASTA) {
            if (res == RESULT_OK && data != null && data.getData() != null) lerPasta(data.getData());
            else js("window.pastaNativaFim&&window.pastaNativaFim(-1)");
            return;
        }
        if (req == REQ_ARQUIVOS && seletor != null) {
            seletor.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
            seletor = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true);   // volta para a tela inicial sem fechar (não perde o que está aberto)
    }

    private void aviso(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private static String nomeSeguro(String n) {
        String x = n == null ? "" : n.replaceAll("[^A-Za-z0-9._-]", "_");
        return x.isEmpty() ? "jogo.sfc" : x;
    }

    /** Funções que a página chama (window.SnesBridge.*). */
    private class Ponte {

        /** Grava a ROM e abre no emulador. Retorna "ok..." ou uma mensagem de erro. */
        @JavascriptInterface
        public String testar(String b64, String nome, String pacote) {
            try {
                byte[] dados = Base64.decode(b64, Base64.DEFAULT);
                File dir = new File(getFilesDir(), "rom");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, nomeSeguro(nome));
                try (FileOutputStream o = new FileOutputStream(f)) {
                    o.write(dados);
                }
                Uri uri = FileProvider.getUriForFile(MainActivity.this,
                        "br.snesbuild.app.fileprovider", f);

                Intent it = new Intent(Intent.ACTION_VIEW);
                it.setDataAndType(uri, "application/octet-stream");
                it.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_ACTIVITY_NEW_TASK);
                if (pacote != null && !pacote.trim().isEmpty()) {
                    it.setPackage(pacote.trim());
                    try {
                        startActivity(it);
                        return "ok: enviado para " + pacote.trim();
                    } catch (ActivityNotFoundException e) {
                        // o pacote não existe ou não aceita abrir ROM: cai para a lista
                        it.setPackage(null);
                    }
                }
                Intent esc = Intent.createChooser(it, "Abrir ROM com…");
                esc.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(esc);
                return "ok: escolha o emulador na lista";
            } catch (Exception e) {
                return "erro: " + e;
            }
        }

        /** Abre o seletor de PASTA do Android (o WebView não sabe escolher pastas sozinho). */
        @JavascriptInterface
        public void escolherPasta() {
            runOnUiThread(() -> {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_PASTA);
                } catch (Exception e) {
                    aviso("Este aparelho não tem seletor de pastas: " + e);
                    js("window.pastaNativaFim&&window.pastaNativaFim(-1)");
                }
            });
        }

        /** Salva o arquivo em Downloads/SNES. */
        @JavascriptInterface
        public String salvar(String b64, String nome) {
            try {
                byte[] dados = Base64.decode(b64, Base64.DEFAULT);
                String n = nomeSeguro(nome);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Downloads.DISPLAY_NAME, n);
                    v.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                    v.put(MediaStore.Downloads.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/SNES");
                    Uri u = getContentResolver().insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (u == null) return "erro: não consegui criar o arquivo";
                    try (OutputStream o = getContentResolver().openOutputStream(u)) {
                        o.write(dados);
                    }
                } else {
                    File d = new File(Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS), "SNES");
                    d.mkdirs();
                    try (FileOutputStream o = new FileOutputStream(new File(d, n))) {
                        o.write(dados);
                    }
                }
                aviso("Salvo em Downloads/SNES/" + n);
                return "Salvo em Downloads/SNES/" + n;
            } catch (Exception e) {
                return "erro: " + e;
            }
        }

        /** Só abre o app do emulador (sem ROM). */
        @JavascriptInterface
        public String abrirEmulador(String pacote) {
            try {
                if (pacote == null || pacote.trim().isEmpty())
                    return "informe o pacote do emulador em Opções";
                Intent it = getPackageManager().getLaunchIntentForPackage(pacote.trim());
                if (it == null) return "não achei o app " + pacote.trim() + " (confira o pacote)";
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                return "abrindo " + pacote.trim();
            } catch (Exception e) {
                return "erro: " + e;
            }
        }
    }
}
