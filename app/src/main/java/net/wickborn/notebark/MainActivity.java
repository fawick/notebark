package net.wickborn.notebark;

import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.speech.RecognizerIntent;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.actions.NoteIntents;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

import javax.mail.Message;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

public class MainActivity extends AppCompatActivity {

    private TextView mTxtSpeechInput;
    private ProgressDialog mProgress;
    private FloatingActionButton mFab;

    private final int REQ_CODE_SPEECH_INPUT = 100;
    private final int REQ_CODE_CHANGE_SETTINGS = 101;

    private static final String RFC822_DATE_FORMAT = "EEE, d MMM yyyy HH:mm:ss Z";
    private static final DateFormat formatter = new SimpleDateFormat(RFC822_DATE_FORMAT);


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        mProgress = new ProgressDialog(this);
        mProgress.setMessage(getString(R.string.sendingMessage));

        useSettings();

        mTxtSpeechInput = (TextView) findViewById(R.id.txtSpeechInput);
        mFab = (FloatingActionButton) findViewById(R.id.fab);
        mFab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                promptSpeechInput();
            }
        });


        Intent intent = getIntent();
        if (intent.getAction().equals(NoteIntents.ACTION_CREATE_NOTE)) {
            String fmt = "%s: %s\r\n";
            String s = String.format(fmt, "Text", intent.getStringExtra(Intent.EXTRA_TEXT));
            s += String.format(fmt, "IntentSubject", intent.getStringExtra(Intent.EXTRA_SUBJECT));
            s += String.format(fmt, "Referrer", intent.getStringExtra(Intent.EXTRA_REFERRER_NAME));
            MakeRequestTask t = sendNote(s);
            if (t != null) {
                boolean fine = true;
                try {
                    t.get();
                } catch (InterruptedException e) {
                    fine = false;
                } catch (ExecutionException e) {
                    e.printStackTrace();
                }
                if (fine) {
                    finish();
                }
            }
        }
    }

    private void useSettings() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        ((TextView) findViewById(R.id.recipient)).setText(
                prefs.getString(SettingsActivity.PREF_KEY_RECIPIENT, ""));
        ((TextView) findViewById(R.id.subject)).setText(
                prefs.getString(SettingsActivity.PREF_KEY_SUBJECT, ""));
    }

    private MakeRequestTask sendNote(String body) {
        if (!isDeviceOnline()) {
            mTxtSpeechInput.setText("No network available!");
            return null;
        }
        mTxtSpeechInput.setText(body);
        MakeRequestTask t = new MakeRequestTask();
        t.execute(body);
        return t;
    }

    /**
     * Showing google speech input dialog
     */
    private void promptSpeechInput() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT,
                getString(R.string.speech_prompt));
        try {
            startActivityForResult(intent, REQ_CODE_SPEECH_INPUT);
        } catch (ActivityNotFoundException a) {
            Toast.makeText(getApplicationContext(),
                    getString(R.string.speech_not_supported),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Receiving speech input
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch (requestCode) {
            case REQ_CODE_SPEECH_INPUT:
                if (resultCode == RESULT_OK && null != data) {

                    ArrayList<String> result = data
                            .getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                    sendNote(result.get(0));
                }
                break;
            case REQ_CODE_CHANGE_SETTINGS:
                useSettings();
                break;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivityForResult(intent, REQ_CODE_CHANGE_SETTINGS);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }


    /**
     * Checks whether the device currently has a network connection.
     *
     * @return true if the device has a network connection, false otherwise.
     */
    private boolean isDeviceOnline() {
        ConnectivityManager connMgr =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();
        return (networkInfo != null && networkInfo.isConnected());
    }

    /**
     * An asynchronous task that handles the sending of the mail.
     * Placing the API calls in their own task ensures the UI stays responsive.
     */
    private class MakeRequestTask extends AsyncTask<String, Void, String> {
        private Exception mLastError = null;

        @Override
        protected String doInBackground(String... params) {
            String result = "";
            try {
                for (String body : params) {
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
                    String server = prefs.getString(SettingsActivity.PREF_KEY_SERVER, "");
                    int port = 465;
                    int colonIdx = server.indexOf(':');
                    if (colonIdx >= 0) {
                        port = Integer.parseInt(server.substring(colonIdx+1));
                        server = server.substring(0, colonIdx);
                    }
                    Properties props = new Properties();
                    props.put("mail.transport.protocol", "smtps");
                    props.put("mail.smtps.host", server);
                    props.put("mail.smtps.auth", "true");
                    Session mailSession = Session.getDefaultInstance(props);
//                    mailSession.setDebug(true);
                    Transport transport = mailSession.getTransport();

                    MimeMessage message = new MimeMessage(mailSession);
                    message.setSubject(prefs.getString(SettingsActivity.PREF_KEY_SUBJECT, ""));
                    message.setText(body);

                    InternetAddress sender = (new InternetAddress(
                            prefs.getString(SettingsActivity.PREF_KEY_SENDER, "")));
                    message.setSender(sender);
                    message.setFrom(sender);
                    message.setSentDate(new Date());

                    String to = prefs.getString(SettingsActivity.PREF_KEY_RECIPIENT, "");
                    message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));


                    String user = prefs.getString(SettingsActivity.PREF_KEY_USERNAME, "");
                    String pwd = prefs.getString(SettingsActivity.PREF_KEY_PASSWORD, "");
                    transport.connect(server, port, user, pwd);
                    transport.sendMessage(message, message.getRecipients(Message.RecipientType.TO));
                    transport.close();
                }
            } catch (Exception e) {
                mLastError = e;
                cancel(true);
            }
            return result;
        }

        @Override
        protected void onPreExecute() {
            mProgress.show();
        }

        @Override
        protected void onPostExecute(String output) {
            mProgress.hide();
        }

        @Override
        protected void onCancelled() {
            mProgress.hide();
            if (mLastError != null) {
                mTxtSpeechInput.setText("The following error occurred:\n"
                        + mLastError.toString());
            } else {
                mTxtSpeechInput.setText("Request cancelled.");
            }
        }
    }
}

