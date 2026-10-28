package app.framewise;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

/** Receives PackageInstaller session results for in-app updates. Not exported. */
public final class UpdateReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!UpdateManager.ACTION_INSTALL_STATUS.equals(intent.getAction())) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION: {
                Intent confirm = Build.VERSION.SDK_INT >= 33
                    ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null) { UpdateManager.publish("error", "Android did not show the install confirmation. Try again.", null); return; }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    UpdateManager.publish("installing", "Confirm the update in Android's dialog. Framewise restarts after installing.", null);
                } catch (RuntimeException error) {
                    UpdateManager.publish("error", "Android blocked the install dialog. Keep Framewise open and tap Install again.", null);
                }
                return;
            }
            case PackageInstaller.STATUS_SUCCESS:
                UpdateManager.publish("installed", "Update installed.", null);
                return;
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                UpdateManager.publish("available", "Update cancelled. Tap Install update to try again.", null);
                return;
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                UpdateManager.publish("error", "Android refused the update because it is signed differently from the installed app. "
                    + "Uninstall Framewise once, then install the APK from the GitHub release page.", null);
                return;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                UpdateManager.publish("error", "Not enough storage to install the update.", null);
                return;
            default:
                UpdateManager.publish("error", "Install failed" + (message == null ? "." : ": " + message), null);
        }
    }
}
