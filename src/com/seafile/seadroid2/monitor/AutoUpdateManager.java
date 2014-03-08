package com.seafile.seadroid2.monitor;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

import android.os.Handler;
import android.util.Log;

import com.seafile.seadroid2.ConcurrentAsyncTask;
import com.seafile.seadroid2.Utils;
import com.seafile.seadroid2.account.Account;
import com.seafile.seadroid2.data.SeafCachedFile;
import com.seafile.seadroid2.transfer.TransferService;

/**
 * Update modified files, retry until success
 *
 */
public class AutoUpdateManager implements Runnable {

	private static final String DEBUG_TAG = "AutoUpdateManager";

	private TransferService txService;
	private Thread thread;
	private boolean running;
	private static final int CHECK_INTERVAL_MILLI = 3000;
	private final Handler mHandler = new Handler();

	private Set<AutoUpdateInfo> infos = new HashSet<AutoUpdateInfo>();

	private MonitorDBHelper db = MonitorDBHelper.getMonitorDBHelper();

	public void onTransferServiceConnected(TransferService txService) {
		this.txService = txService;
		running = true;
		thread = new Thread(this);
		thread.start();
	}

	public void stop() {
		running = false;
	}

	/**
	 * This method is called by file monitor, so it would be executed in the
	 * file monitor thread
	 */
	public void addTask(Account account, SeafCachedFile cachedFile,
			File localFile) {

		AutoUpdateInfo info = new AutoUpdateInfo(account, cachedFile.repoID,
				cachedFile.repoName, Utils.getParentPath(cachedFile.path),
				localFile.getPath());

		synchronized (infos) {
			if (infos.contains(info)) {
				return;
			}
			infos.add(info);
		}

		db.saveAutoUpdateInfo(info);

		if (!Utils.isNetworkOn() || txService == null) {
			return;
		}

		addUpdateTask(info);
	}

	private void addUpdateTask(final AutoUpdateInfo info) {
		mHandler.post(new Runnable() {
			@Override
			public void run() {
				txService.addUploadTask(info.account, info.repoID,
						info.repoName, info.parentDir, info.localPath, true);
			}
		});
	}

	public void onFileUpdateSuccess(Account account, String repoID,
			String parentDir, String localPath) {

		AutoUpdateInfo foundedInfo = null;
		synchronized (infos) {
			for (AutoUpdateInfo info : infos) {
				if (info.account == account && info.repoID == repoID
						&& info.parentDir == parentDir
						&& info.localPath == localPath) {
					Log.d(DEBUG_TAG, "auto updated " + localPath);
					foundedInfo = info;
					infos.remove(info);
					break;
				}
			}
		}

		if (foundedInfo != null) {
			final AutoUpdateInfo info = foundedInfo;
			ConcurrentAsyncTask.execute(new Runnable() {
				@Override
				public void run() {
					db.removeAutoUpdateInfo(info);
				}
			});
		}
	}

	/**
	 * Checks the upload tasks and schedule them to run
	 **/
	private void scheduleUpdateTasks() {
		int size = infos.size();
		if (!Utils.isNetworkOn()) {
			Log.d(DEBUG_TAG, "network is not available, " + size + " in queue");
			return;
		}

		if (txService == null) {
			return;
		}

		Log.d(DEBUG_TAG,
				String.format("check auto upload tasks, %d in queue", size));

		synchronized (infos) {
			for (AutoUpdateInfo info : infos) {
				addUpdateTask(info);
			}
		}
	}

	public void run() {
		synchronized (infos) {
			infos.addAll(db.getAutoUploadInfos());
		}

		while (running) {
			scheduleUpdateTasks();
			if (!running) {
				break;
			}
			try {
				Thread.sleep(CHECK_INTERVAL_MILLI);
			} catch (final InterruptedException ignored) {
				break;
			}
		}
	}

}

class AutoUpdateInfo {
	final Account account;
	final String repoID;
	final String repoName;
	final String parentDir;
	final String localPath;

	public AutoUpdateInfo(Account account, String repoID, String repoName,
			String parentDir, String localPath) {

		this.account = account;
		this.repoID = repoID;
		this.repoName = repoName;
		this.parentDir = parentDir;
		this.localPath = localPath;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null || (obj.getClass() != this.getClass()))
			return false;

		AutoUpdateInfo a = (AutoUpdateInfo) obj;

		return this.account == a.account && this.repoID == a.repoID
                && this.repoName == a.repoName
				&& this.parentDir == a.parentDir
				&& this.localPath == a.localPath;
	}

	private volatile int hashCode = 0;

	@Override
	public int hashCode() {
		int result = hashCode;
		if (result == 0) {
			result = 17;
			result = 31 * result + account.hashCode();
			result = 31 * result + repoID.hashCode();
			result = 31 * result + repoName.hashCode();
			result = 31 * result + parentDir.hashCode();
			result = 31 * result + localPath.hashCode();
			hashCode = result;
		}
		return result;
	}
}
