package com.growthbeat.link;

import java.util.HashMap;
import java.util.Map;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;

import com.growthbeat.CatchableThread;
import com.growthbeat.GrowthbeatCore;
import com.growthbeat.GrowthbeatException;
import com.growthbeat.Logger;
import com.growthbeat.Preference;
import com.growthbeat.analytics.GrowthAnalytics;
import com.growthbeat.http.GrowthbeatHttpClient;
import com.growthbeat.link.handler.DefaultInstallReceiveHandler;
import com.growthbeat.link.handler.InstallReceiveHandler;
import com.growthbeat.link.callback.DefaultSynchronizationCallback;
import com.growthbeat.link.callback.SynchronizationCallback;
import com.growthbeat.link.model.Click;
import com.growthbeat.link.model.Synchronization;
import com.growthbeat.utils.AppUtils;

public class GrowthLink {

	public static final String LOGGER_DEFAULT_TAG = "GrowthLink";
	public static final String HTTP_CLIENT_DEFAULT_BASE_URL = "https://api.link.growthbeat.com/";
	private static final String DEFAULT_SYNCRONIZATION_URL = "http://gbt.io/l/synchronize";
	private static final int HTTP_CLIENT_DEFAULT_CONNECTION_TIMEOUT = 60 * 1000;
	private static final int HTTP_CLIENT_DEFAULT_SOCKET_TIMEOUT = 60 * 1000;
	public static final String PREFERENCE_DEFAULT_FILE_NAME = "growthlink-preferences";

	private static final GrowthLink instance = new GrowthLink();
	private final Logger logger = new Logger(LOGGER_DEFAULT_TAG);
	private final GrowthbeatHttpClient httpClient = new GrowthbeatHttpClient(HTTP_CLIENT_DEFAULT_BASE_URL,
			HTTP_CLIENT_DEFAULT_CONNECTION_TIMEOUT, HTTP_CLIENT_DEFAULT_SOCKET_TIMEOUT);
	private final Preference preference = new Preference(PREFERENCE_DEFAULT_FILE_NAME);

	private Context context = null;
	private String applicationId = null;
	private String credentialId = null;
	private String syncronizationUrl = null;

	private boolean initialized = false;
	private boolean isFirstSession = false;
	
	private SynchronizationCallback callback = null;
	
	private InstallReceiveHandler receiveHandler = new DefaultInstallReceiveHandler();

	private SynchronizationCallback synchronizationCallback = new DefaultSynchronizationCallback();

	private GrowthLink() {
		super();
	}

	public static GrowthLink getInstance() {
		return instance;
	}

	public void initialize(final Context context, final String applicationId, final String credentialId, SynchronizationCallback callback) {
		if (initialized)
			return;
		initialized = true;

		if (context == null) {
			logger.warning("The context parameter cannot be null.");
			return;
		}

		this.context = context.getApplicationContext();
		this.applicationId = applicationId;
		this.credentialId = credentialId;
		this.syncronizationUrl = DEFAULT_SYNCRONIZATION_URL;
		if (callback != null)
			this.synchronizationCallback = callback;

		GrowthbeatCore.getInstance().initialize(context, applicationId, credentialId);
		this.preference.setContext(GrowthbeatCore.getInstance().getContext());
		if (GrowthbeatCore.getInstance().getClient() == null
				|| (GrowthbeatCore.getInstance().getClient().getApplication() != null && !GrowthbeatCore.getInstance().getClient()
						.getApplication().getId().equals(applicationId))) {
			preference.removeAll();
		}

		GrowthAnalytics.getInstance().initialize(context, applicationId, credentialId);

		synchronize();
	}

	public void initialize(final Context context, final String applicationId, final String credentialId) {
		this.initialize(context, applicationId, credentialId, null);
	}

	public String getSyncronizationUrl() {
		return syncronizationUrl;
	}

	public void setSyncronizationUrl(String syncronizationUrl) {
		this.syncronizationUrl = syncronizationUrl;
	}

	public void handleOpenUrl(Uri uri) {

		if (uri == null)
			return;

		final String clickId = uri.getQueryParameter("clickId");
		if (clickId == null) {
			logger.info("Unabled to get clickId from url.");
			return;
		}

		final String uuid = uri.getQueryParameter("uuid");
		if (uuid != null) {
			GrowthAnalytics.getInstance().setUUID(uuid);
		}

		final Handler handler = new Handler();
		new Thread(new Runnable() {
			@Override
			public void run() {

				logger.info("Deeplinking...");

				try {

					final Click click = Click.deeplink(GrowthbeatCore.getInstance().waitClient().getId(), clickId, isFirstSession,
							credentialId);
					if (click == null || click.getPattern() == null || click.getPattern().getLink() == null) {
						logger.error("Failed to deeplink.");
						return;
					}

					logger.info(String.format("Deeplink success. (clickId: %s)", click.getId()));

					handler.post(new Runnable() {
						@Override
						public void run() {

							Map<String, String> properties = new HashMap<String, String>();
							properties.put("linkId", click.getPattern().getLink().getId());
							properties.put("patternId", click.getPattern().getId());
							if (click.getPattern().getIntent() != null)
								properties.put("intentId", click.getPattern().getIntent().getId());

							if (isFirstSession)
								GrowthAnalytics.getInstance().track("GrowthLink", "Install", properties, null);

							GrowthAnalytics.getInstance().track("GrowthLink", "Open", properties, null);

							isFirstSession = false;

							if (click.getPattern().getIntent() != null) {
								GrowthbeatCore.getInstance().handleIntent(click.getPattern().getIntent());
							}

						}
					});

				} catch (GrowthbeatException e) {
					logger.info(String.format("Synchronization is not found.", e.getMessage()));
				}

			}

		}).start();

	}

	private void synchronize() {

		logger.info("Check initialization...");
		if (Synchronization.load() != null) {
			logger.info("Already initialized.");
			return;
		}

		isFirstSession = true;

		final Handler handler = new Handler();
		new Thread(new Runnable() {
			@Override
			public void run() {

				logger.info("Synchronizing...");

				try {

					String version = AppUtils.getaAppVersion(context);
					final Synchronization synchronization = Synchronization.synchronize(applicationId, version, credentialId);
					if (synchronization == null) {
						logger.error("Failed to Synchronize.");
						return;
					}

					Synchronization.save(synchronization);
					logger.info(String.format("Synchronize success. (browser: %s)", synchronization.getBrowser()));

					handler.post(new Runnable() {
						public void run() {
							if (GrowthLink.this.synchronizationCallback != null) {
								GrowthLink.this.synchronizationCallback.onComplete(synchronization);
							}
						}
					});

				} catch (GrowthbeatException e) {
					logger.info(String.format("Synchronization is not found. %s", e.getMessage()));
				}

			}

		}).start();

	}

	public Context getContext() {
		return context;
	}

	public String getApplicationId() {
		return applicationId;
	}

	public String getCredentialId() {
		return credentialId;
	}

	public Logger getLogger() {
		return logger;
	}

	public GrowthbeatHttpClient getHttpClient() {
		return httpClient;
	}

	public Preference getPreference() {
		return preference;
	}

	private static class Thread extends CatchableThread {

		public Thread(Runnable runnable) {
			super(runnable);
		}

		@Override
		public void uncaughtException(java.lang.Thread thread, Throwable e) {
			String link = "Uncaught Exception: " + e.getClass().getName();
			if (e.getMessage() != null)
				link += "; " + e.getMessage();
			GrowthLink.getInstance().getLogger().warning(link);
			e.printStackTrace();
		}

	}
	
	public void setInstallReceiveHandler(InstallReceiveHandler receiveHandler) {
		this.receiveHandler = receiveHandler;
	}

	public InstallReceiveHandler getInstallReceiveHandler() {
		return receiveHandler;
	}


}
