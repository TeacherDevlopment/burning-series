package de.m4lik.burningseries.util;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.support.v4.app.FragmentActivity;
import android.support.v4.content.FileProvider;
import android.util.Log;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import de.m4lik.burningseries.AppComponent;
import de.m4lik.burningseries.BuildConfig;
import de.m4lik.burningseries.Dagger;
import de.m4lik.burningseries.services.DownloadService;
import de.m4lik.burningseries.services.objects.GsonAdaptersUpdate;
import de.m4lik.burningseries.services.objects.ImmutableUpdate;
import de.m4lik.burningseries.services.objects.Update;
import de.m4lik.burningseries.ui.dialogs.DownloadUpdateDialog;
import retrofit2.Call;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;
import retrofit2.http.GET;
import retrofit2.http.Path;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Actions;
import rx.util.async.Async;

import static de.m4lik.burningseries.ui.dialogs.ErrorDialog.defaultOnError;

/**
 * Created by Malik on 28.01.2017
 *
 * @author Malik Mann
 */

public class Updater {

    private final int currentVersion;
    private final ImmutableList<String> endpoints;
    private String channel;

    public Updater(Context context) {
        currentVersion = AndroidUtility.buildNumber();

        boolean betaChannel = Settings.of(context).isBetaChannel();
        endpoints = updateUrls();
        channel = betaChannel ? "beta" : "stable";
    }

    private static Retrofit buildRetrofit(String baseURL) {

        return new Retrofit.Builder()
                .baseUrl(baseURL)
                .addConverterFactory(GsonConverterFactory.create(
                        new GsonBuilder()
                                .registerTypeAdapterFactory(new GsonAdaptersUpdate())
                                .create()
                ))
                .build();
    }

    /**
     * Returns the Endpoint-URL that is to be queried
     */
    private static ImmutableList<String> updateUrls() {
        List<String> urls = new ArrayList<>();
        urls.add("http://bs.malikmann.de/");
        return ImmutableList.copyOf(urls);
    }

    public static void download(FragmentActivity activity, Update update) {
        Log.d("BS-Updater", "Trying to download...");
        AppComponent appComponent = Dagger.appComponent(activity);
        Observable<DownloadService.Status> progress = appComponent.downloadService()
                .downloadUpdate(update.apk())
                .subscribeOn(BackgroundScheduler.instance())
                .unsubscribeOn(BackgroundScheduler.instance())
                .observeOn(AndroidSchedulers.mainThread())
                .share();

        // install on finish
        final Context appContext = activity.getApplicationContext();
        progress.filter(DownloadService.Status::finished)
                .flatMap(status -> {
                    try {
                        install(appContext, status.file);
                        return Observable.empty();

                    } catch (IOException error) {
                        return Observable.error(error);
                    }
                })
                .subscribe(Actions.empty(), defaultOnError());

        // show a progress dialog
        DownloadUpdateDialog dialog = new DownloadUpdateDialog(progress);
        dialog.show(activity.getSupportFragmentManager(), null);

        // remove pending upload notification
        appComponent.notificationService().cancelForUpdate();
    }

    @TargetApi(Build.VERSION_CODES.KITKAT)
    private static void install(Context context, File apk) throws IOException {
        Log.d("BS-Updater", "Trying to install...");
        Uri uri = Uri.parse("");
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            String provider = BuildConfig.APPLICATION_ID + ".FileProvider";
            try {
                uri = FileProvider.getUriForFile(context, provider, apk);
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            File file = new File(context.getExternalCacheDir(), "update.apk");
            try (InputStream input = new FileInputStream(apk)) {
                try (OutputStream output = new FileOutputStream(file)) {
                    ByteStreams.copy(input, output);
                }
            }

            // make file readable
            if (file.setReadable(true))
                uri = Uri.fromFile(file);
        }

        Log.d("BS-Updater", "Starting install intent...");

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
    }

    private Observable<Update> check(final String endpoint) {
        return
                Async.fromCallable(() -> {
                            UpdateApi api = buildRetrofit(endpoint).create(UpdateApi.class);
                            return api.check(channel).execute().body();
                        }, BackgroundScheduler.instance()
                ).filter(
                        update -> update.buildNumber() > currentVersion
                ).map(update ->
                        ImmutableUpdate.builder()
                                .buildNumber(update.buildNumber())
                                .versionName(update.versionName())
                                .changelog(update.changelog())
                                .apk(update.apk())
                                .build()
                );
    }

    public Observable<Update> check() {
        return Observable.from(endpoints)
                .flatMap(ep -> check(ep)
                        .doOnError(err -> {
                            Logger.warn("Could not check for update at " + ep + ": " + err.toString());
                            err.printStackTrace();
                        })
                        .onErrorResumeNext(Observable.empty())
                )
                .take(1);
    }

    private interface UpdateApi {
        @GET("/version/{channel}/update.json")
        Call<Update> check(@Path("channel") String channel);
    }
}