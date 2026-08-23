package eu.kanade.tachiyomi.extension.pt.tomato;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.hcaptcha.sdk.HCaptcha;
import com.hcaptcha.sdk.HCaptchaConfig;
import com.hcaptcha.sdk.HCaptchaException;
import com.hcaptcha.sdk.HCaptchaRenderMode;
import com.hcaptcha.sdk.HCaptchaTheme;
import com.hcaptcha.sdk.HCaptchaTokenResponse;
import com.hcaptcha.sdk.tasks.OnFailureListener;
import com.hcaptcha.sdk.tasks.OnLoadedListener;
import com.hcaptcha.sdk.tasks.OnOpenListener;
import com.hcaptcha.sdk.tasks.OnSuccessListener;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONException;
import org.json.JSONObject;

public final class TomatoLoginActivity extends Activity {
  public static final String EXTRA_RESULT_RECEIVER = "tomato_login_result_receiver";
  public static final String EXTRA_SESSION_TOKEN = "tomato_login_session_token";
  public static final String EXTRA_API_HOST = "tomato_login_api_host";
  public static final int RESULT_LOGIN_SUCCESS = 1;

  private static final String PROD_API_HOST = "https://prod-api.tomatoanimes.com";
  private static final String EDGE_API_HOST = "https://edge.betomato.com";
  private static final String HCAPTCHA_SITE_KEY = "d0706611-1d89-4b8c-af79-3caf0f14feba";
  private static final String TAG = "TomatoLogin";
  private static final int HTTP_TIMEOUT_MS = 10_000;
  private static final int SUCCESS_STATUS = 4;

  private EditText username;
  private EditText email;
  private EditText password;
  private Button action;
  private Button changeMode;
  private TextView status;
  private FrameLayout captchaContainer;
  private HCaptcha hCaptcha;
  private ResultReceiver resultReceiver;
  private ExecutorService executor;
  private String apiHost;
  private boolean signUpMode;
  private boolean captchaPending;
  private String pendingUsername;
  private String pendingEmail;
  private String pendingPassword;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    apiHost = validatedApiHost(getIntent().getStringExtra(EXTRA_API_HOST));
    resultReceiver = readResultReceiver();
    executor = Executors.newSingleThreadExecutor();
    setTitle("Conta Tomato");
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    createContentView();
  }

  private void createContentView() {
    final ScrollView scrollView = new ScrollView(this);
    scrollView.setFillViewport(true);

    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setGravity(Gravity.CENTER_HORIZONTAL);
    int padding = Math.round(24 * getResources().getDisplayMetrics().density);
    int spacing = Math.round(8 * getResources().getDisplayMetrics().density);
    root.setPadding(padding, spacing * 2, padding, spacing * 2);

    username = new EditText(this);
    username.setHint("Nome de usuário");
    username.setVisibility(View.GONE);
    username.setInputType(InputType.TYPE_CLASS_TEXT);

    email = new EditText(this);
    email.setHint("E-mail");
    email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

    password = new EditText(this);
    password.setHint("Senha");
    password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

    captchaContainer = new FrameLayout(this);
    captchaContainer.setVisibility(View.GONE);

    action = new Button(this);
    action.setText("Entrar");
    action.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            beginAuthentication();
          }
        });

    changeMode = new Button(this);
    changeMode.setText("Criar conta");
    changeMode.setOnClickListener(
        new View.OnClickListener() {
          @Override
          public void onClick(View view) {
            toggleMode();
          }
        });

    status = new TextView(this);
    status.setGravity(Gravity.CENTER);
    status.setText("Use sua conta oficial Tomato.");

    int match = LinearLayout.LayoutParams.MATCH_PARENT;
    int wrap = LinearLayout.LayoutParams.WRAP_CONTENT;
    root.addView(username, new LinearLayout.LayoutParams(match, wrap));
    LinearLayout.LayoutParams emailParams = new LinearLayout.LayoutParams(match, wrap);
    emailParams.topMargin = spacing;
    root.addView(email, emailParams);
    LinearLayout.LayoutParams passwordParams = new LinearLayout.LayoutParams(match, wrap);
    passwordParams.topMargin = spacing;
    root.addView(password, passwordParams);
    root.addView(captchaContainer, new LinearLayout.LayoutParams(match, 0, 1f));
    LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(match, wrap);
    actionParams.topMargin = spacing * 2;
    root.addView(action, actionParams);
    LinearLayout.LayoutParams changeModeParams = new LinearLayout.LayoutParams(match, wrap);
    changeModeParams.topMargin = spacing;
    root.addView(changeMode, changeModeParams);
    LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(match, wrap);
    statusParams.topMargin = spacing;
    root.addView(status, statusParams);

    scrollView.addView(
        root,
        new ScrollView.LayoutParams(
            ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.MATCH_PARENT));
    scrollView.setOnApplyWindowInsetsListener(
        new View.OnApplyWindowInsetsListener() {
          @Override
          public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
            scrollView.setPadding(
                0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            return insets;
          }
        });
    setContentView(scrollView);
    scrollView.requestApplyInsets();
  }

  private void beginAuthentication() {
    pendingEmail = email.getText().toString().trim();
    pendingPassword = password.getText().toString();
    pendingUsername = username.getText().toString().trim();

    if (signUpMode) {
      if (pendingUsername.length() < 3 || pendingUsername.length() > 24) {
        status.setText("O nome de usuário deve ter entre 3 e 24 caracteres.");
        return;
      }
      if (pendingEmail.isEmpty()) {
        status.setText("Informe o e-mail.");
        return;
      }
      if (pendingPassword.length() < 5 || pendingPassword.length() > 127) {
        status.setText("A senha deve ter entre 5 e 127 caracteres.");
        return;
      }
    } else if (pendingEmail.isEmpty() || pendingPassword.isEmpty()) {
      status.setText("Informe e-mail e senha.");
      return;
    }

    requestCaptcha();
  }

  private void requestCaptcha() {
    clearCaptcha();
    captchaPending = true;
    action.setEnabled(false);
    changeMode.setEnabled(false);
    status.setText(
        signUpMode
            ? "Confirme o hCaptcha para criar a conta..."
            : "Confirme o hCaptcha para entrar...");
    captchaContainer.setVisibility(View.VISIBLE);
    HCaptcha captcha = HCaptcha.getClient(this).setEmbeddedContainer(captchaContainer);
    hCaptcha = captcha;
    HCaptchaConfig config =
        HCaptchaConfig.builder()
            .siteKey(HCAPTCHA_SITE_KEY)
            .theme(HCaptchaTheme.DARK)
            .renderMode(HCaptchaRenderMode.EMBEDDED)
            .build();
    captcha
        .verifyWithHCaptcha(config)
        .addOnLoadedListener(
            new OnLoadedListener() {
              @Override
              public void onLoaded() {
                Log.d(TAG, "TOMATO_DEBUG AUTH human_challenge=loaded");
              }
            })
        .addOnOpenListener(
            new OnOpenListener() {
              @Override
              public void onOpen() {
                Log.d(TAG, "TOMATO_DEBUG AUTH human_challenge=opened");
              }
            })
        .addOnSuccessListener(
            new OnSuccessListener<HCaptchaTokenResponse>() {
              @Override
              public void onSuccess(final HCaptchaTokenResponse response) {
                Log.d(TAG, "TOMATO_DEBUG AUTH human_challenge=completed");
                runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        String verification = response.getTokenResult();
                        if (!captchaPending
                            || verification == null
                            || verification.trim().isEmpty()) {
                          captchaFailed("empty_response");
                          return;
                        }
                        captchaPending = false;
                        clearCaptcha();
                        authenticate(verification);
                      }
                    });
              }
            })
        .addOnFailureListener(
            new OnFailureListener() {
              @Override
              public void onFailure(final HCaptchaException error) {
                Log.d(
                    TAG,
                    "TOMATO_DEBUG AUTH human_challenge=failure type="
                        + error.getClass().getSimpleName());
                runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        captchaFailed("sdk_failure");
                      }
                    });
              }
            });
  }

  private void captchaFailed(String category) {
    if (!captchaPending) {
      return;
    }
    Log.e(TAG, "HCAPTCHA error=" + category);
    captchaPending = false;
    clearCaptcha();
    action.setEnabled(true);
    changeMode.setEnabled(true);
    status.setText("Não foi possível concluir o hCaptcha.");
  }

  private void clearCaptcha() {
    if (hCaptcha != null) {
      hCaptcha.destroy();
      hCaptcha = null;
    }
    if (captchaContainer != null) {
      captchaContainer.removeAllViews();
      captchaContainer.setVisibility(View.GONE);
    }
  }

  private void authenticate(final String verification) {
    final boolean registering = signUpMode;
    final String path = registering ? "/register/" : "/login/";
    final JSONObject payload = new JSONObject();
    try {
      if (registering) {
        payload.put("username", pendingUsername);
      }
      payload.put("email", pendingEmail);
      payload.put("password", pendingPassword);
      payload.put("verification", verification);
      payload.put("fingerprint", deviceFingerprint());
    } catch (JSONException error) {
      action.setEnabled(true);
      changeMode.setEnabled(true);
      status.setText("Falha interna de autenticação.");
      return;
    }
    status.setText(registering ? "Criando conta..." : "Entrando...");

    executor.execute(
        new Runnable() {
          @Override
          public void run() {
            AuthResult result;
            try {
              result = executeAuthentication(path, payload, registering);
            } catch (IOException error) {
              result = AuthResult.failure("Falha de conexão. Tente novamente.");
            } catch (Exception error) {
              result = AuthResult.failure("Falha interna de autenticação.");
            }
            final AuthResult completed = result;
            runOnUiThread(
                new Runnable() {
                  @Override
                  public void run() {
                    showAuthenticationResult(completed, registering);
                  }
                });
          }
        });
  }

  private AuthResult executeAuthentication(String path, JSONObject payload, boolean registering)
      throws IOException {
    HttpsURLConnection connection = (HttpsURLConnection) new URL(apiHost + path).openConnection();
    connection.setRequestMethod("POST");
    connection.setConnectTimeout(HTTP_TIMEOUT_MS);
    connection.setReadTimeout(HTTP_TIMEOUT_MS);
    connection.setDoOutput(true);
    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
    try {
      OutputStreamWriter writer =
          new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8);
      try {
        writer.write(payload.toString());
      } finally {
        writer.close();
      }

      int httpStatus = connection.getResponseCode();
      String rawBody =
          readBody(httpStatus >= 400 ? connection.getErrorStream() : connection.getInputStream());
      JSONObject body;
      try {
        body = new JSONObject(rawBody);
      } catch (Exception ignored) {
        body = null;
      }
      int apiStatus = parseApiStatus(body);
      String message = body == null ? null : safeMessage(body.optString("message", null));
      String token = body == null ? "" : body.optString("token", "").trim();
      if (httpStatus < 200 || httpStatus > 299 || apiStatus != SUCCESS_STATUS) {
        return AuthResult.failure(authenticationError(apiStatus, message, httpStatus, registering));
      }
      if (token.isEmpty()) {
        return AuthResult.failure(
            authenticationError(apiStatus, "Resposta sem sessão", httpStatus, registering));
      }
      return AuthResult.success(token);
    } finally {
      connection.disconnect();
    }
  }

  private void showAuthenticationResult(AuthResult result, boolean registering) {
    action.setEnabled(true);
    changeMode.setEnabled(true);
    if (result.token == null) {
      status.setText(result.error);
      return;
    }
    if (resultReceiver == null) {
      status.setText("Não foi possível entregar a sessão ao Mihon.");
      return;
    }
    Bundle data = new Bundle();
    data.putString(EXTRA_SESSION_TOKEN, result.token);
    resultReceiver.send(RESULT_LOGIN_SUCCESS, data);
    password.getText().clear();
    status.setText(registering ? "Conta criada e conectada" : "Conectado");
    setResult(RESULT_OK);
    finish();
  }

  private void toggleMode() {
    signUpMode = !signUpMode;
    username.setVisibility(signUpMode ? View.VISIBLE : View.GONE);
    action.setText(signUpMode ? "Criar conta" : "Entrar");
    changeMode.setText(signUpMode ? "Voltar para entrar" : "Criar conta");
    status.setText(signUpMode ? "Crie sua conta oficial Tomato." : "Use sua conta oficial Tomato.");
  }

  private String authenticationError(
      int apiStatus, String message, int httpStatus, boolean registering) {
    if (registering) {
      switch (apiStatus) {
        case 8:
          return "Não foi possível criar a conta.";
        case 9:
          return "Já existe uma conta com esses dados.";
        case 10:
          return "Dados de cadastro inválidos.";
        case 11:
        case 12:
          return "Nome de usuário inválido.";
        case 30:
          return "hCaptcha inválido ou expirado.";
        default:
          return message != null ? message : "Erro da API Tomato (HTTP " + httpStatus + ").";
      }
    }
    if (apiStatus == 1) {
      return "Credenciais inválidas.";
    }
    if (apiStatus == 30) {
      return "hCaptcha inválido ou expirado.";
    }
    return message != null ? message : "Erro da API Tomato (HTTP " + httpStatus + ").";
  }

  private int parseApiStatus(JSONObject body) {
    if (body == null) {
      return -1;
    }
    Object value = body.opt("status_code");
    if (value instanceof Number) {
      return ((Number) value).intValue();
    }
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private String readBody(InputStream stream) throws IOException {
    if (stream == null) {
      return "";
    }
    BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
    try {
      StringBuilder result = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) {
        result.append(line);
      }
      return result.toString();
    } finally {
      reader.close();
    }
  }

  private String safeMessage(String message) {
    if (message == null) {
      return null;
    }
    String sanitized = message.replaceAll("[\\r\\n]+", " ");
    return sanitized.length() > 200 ? sanitized.substring(0, 200) : sanitized;
  }

  private String deviceFingerprint() {
    return (Build.VERSION.RELEASE + "/" + Build.MANUFACTURER + "/" + Build.MODEL)
        .replaceAll("\\s", "-");
  }

  private String validatedApiHost(String requestedHost) {
    if (PROD_API_HOST.equals(requestedHost) || EDGE_API_HOST.equals(requestedHost)) {
      return requestedHost;
    }
    return PROD_API_HOST;
  }

  @SuppressWarnings("deprecation")
  private ResultReceiver readResultReceiver() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
    }
    return getIntent().getParcelableExtra(EXTRA_RESULT_RECEIVER);
  }

  @Override
  protected void onDestroy() {
    captchaPending = false;
    clearCaptcha();
    if (executor != null) {
      executor.shutdownNow();
    }
    super.onDestroy();
  }

  private static final class AuthResult {
    private final String token;
    private final String error;

    private AuthResult(String token, String error) {
      this.token = token;
      this.error = error;
    }

    private static AuthResult success(String token) {
      return new AuthResult(token, null);
    }

    private static AuthResult failure(String error) {
      return new AuthResult(null, error);
    }
  }
}
