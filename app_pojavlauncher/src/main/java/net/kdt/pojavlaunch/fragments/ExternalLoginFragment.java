package net.kdt.pojavlaunch.fragments;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.authenticator.yggdrasil.YggdrasilClient;
import net.kdt.pojavlaunch.authenticator.yggdrasil.YggdrasilClient.AuthResult;
import net.kdt.pojavlaunch.authenticator.yggdrasil.YggdrasilClient.YggdrasilException;
import net.kdt.pojavlaunch.extra.ExtraConstants;
import net.kdt.pojavlaunch.extra.ExtraCore;
import net.kdt.pojavlaunch.value.MinecraftAccount;

/**
 * Login against a third party Yggdrasil (authlib-injector) server such as LittleSkin or Ely.by.
 * The resulting account stores the API root, which makes Tools.launchGame() inject
 * authlib-injector and force userType=mojang for it.
 */
public class ExternalLoginFragment extends Fragment {
    public static final String TAG = "EXTERNAL_LOGIN_FRAGMENT";
    private static final String TAG_LOG = "ExternalLogin";
    public static final String DEFAULT_API_ROOT = "https://littleskin.cn/api/yggdrasil";

    private EditText mServerEditText;
    private EditText mUsernameEditText;
    private EditText mPasswordEditText;
    private View mLoginButton;

    public ExternalLoginFragment() {
        super(R.layout.fragment_external_login);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        mServerEditText = view.findViewById(R.id.ext_login_server);
        mUsernameEditText = view.findViewById(R.id.ext_login_username);
        mPasswordEditText = view.findViewById(R.id.ext_login_password);
        mLoginButton = view.findViewById(R.id.ext_login_button);

        mServerEditText.setText(DEFAULT_API_ROOT);

        mLoginButton.setOnClickListener(v -> performLogin());
    }

    private void performLogin() {
        final String server = YggdrasilClient.normalizeRoot(mServerEditText.getText().toString());
        final String username = mUsernameEditText.getText().toString().trim();
        final String password = mPasswordEditText.getText().toString();
        final Context context = requireContext();

        if (server == null || server.isEmpty() || username.isEmpty() || password.isEmpty()) {
            Tools.dialog(context, getString(R.string.ext_login_missing_title),
                    getString(R.string.ext_login_missing_text));
            return;
        }

        mLoginButton.setEnabled(false);
        new Thread(() -> {
            try {
                // Cache the API metadata so the game can start without reaching the auth server
                String prefetched = null;
                try {
                    prefetched = YggdrasilClient.fetchMetadataPrefetched(server);
                } catch (Exception ignored) {
                    Log.w(TAG_LOG, "Could not prefetch API metadata, the game will fetch it itself", ignored);
                }

                AuthResult result = YggdrasilClient.authenticate(server, username, password, null);
                String name = result.selectedProfile != null && result.selectedProfile.name != null
                        ? result.selectedProfile.name : username;
                String uuid = result.selectedProfile != null ? result.selectedProfile.id : "";

                MinecraftAccount account = MinecraftAccount.load(name);
                if (account == null) account = new MinecraftAccount();
                account.username = name;
                account.accessToken = result.accessToken;
                account.clientToken = result.clientToken;
                account.profileId = uuid;
                account.isMicrosoft = false;
                account.yggdrasilUrl = server;
                account.yggdrasilPrefetched = prefetched;
                account.msaRefreshToken = "0";
                account.xuid = null;
                account.save();

                final String finalName = name;
                Tools.runOnUiThread(() -> {
                    if (getActivity() == null) return;
                    Toast.makeText(context, R.string.ext_login_done, Toast.LENGTH_SHORT).show();
                    ExtraCore.setValue(ExtraConstants.EXTERNAL_LOGIN_TODO, finalName);
                    Tools.swapFragment(requireActivity(), MainMenuFragment.class, MainMenuFragment.TAG, null);
                });
            } catch (YggdrasilException e) {
                Log.w(TAG_LOG, "Yggdrasil login rejected", e);
                final String message = describeError(e);
                Tools.runOnUiThread(() -> {
                    mLoginButton.setEnabled(true);
                    if (getActivity() == null) return;
                    Tools.dialog(context, getString(R.string.ext_login_failed_title), message);
                });
            } catch (Exception e) {
                Log.e(TAG_LOG, "Yggdrasil login failed", e);
                final String message = e.toString();
                Tools.runOnUiThread(() -> {
                    mLoginButton.setEnabled(true);
                    if (getActivity() == null) return;
                    Tools.showError(context, message, e);
                });
            }
        }, "YggdrasilLogin").start();
    }

    private String describeError(YggdrasilException e) {
        if (e.errorMessage != null && !e.errorMessage.isEmpty()) return e.errorMessage;
        if (e.error != null && !e.error.isEmpty()) return e.error;
        return getString(R.string.ext_login_failed_generic, e.httpCode);
    }
}
