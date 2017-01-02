package me.xiu.xiu.campusvideo.dao.offline;

import android.os.SystemClock;
import android.support.v4.util.LongSparseArray;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import me.xiu.xiu.campusvideo.aidls.Offlining;
import me.xiu.xiu.campusvideo.common.OfflineState;
import me.xiu.xiu.campusvideo.dao.DaoAlias;
import me.xiu.xiu.campusvideo.dao.DatabaseHelper;
import me.xiu.xiu.campusvideo.util.FileUtils;
import me.xiu.xiu.campusvideo.util.Logger;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.internal.Util;
import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Action0;
import rx.schedulers.Schedulers;

/**
 * Created by felix on 16/5/4.
 */
public class OffliningDelegate {
    private static final String TAG = "OffliningDelegate";

    private OfflineDao mOfflineDao;

    private Scheduler mOffliningScheduler;

    private OffliningCallback mCallback;

    private volatile LongSparseArray<Offlining> mOfflinings;

    private volatile LongSparseArray<Subscription> mSubscriptions;

    private static final int MAX_THREAD = 3;

    private static final int BUFFER_SIZE = 8192;

    private static final long UPDATE_GAP = 1000;

    public OffliningDelegate(OffliningCallback callback) throws Exception {
        mCallback = callback;
        mOfflinings = new LongSparseArray<>();
        mSubscriptions = new LongSparseArray<>();
        mOfflineDao = DatabaseHelper.getDao(DaoAlias.OFFLINE);
        mOffliningScheduler = Schedulers.from(Executors.newFixedThreadPool(MAX_THREAD));
    }

    public void sync(Action0 callback) {
        Observable.just(getMaxId())
                .observeOn(Schedulers.io())
                .map(maxId -> {
                    try {
                        List<Offlining> offlinings = mOfflineDao.queryOfflinings(maxId);
                        for (Offlining offlining : offlinings) {
                            mOfflinings.put(offlining.getId(), offlining);
                        }
                        return mOfflinings.size();
                    } catch (SQLException ignored) {
                        return 0;
                    }
                })
                .subscribe(count -> {
                    if (callback != null) {
                        callback.call();
                    }
                }, throwable -> {
                    Logger.w(TAG, throwable);
                });
    }

    public List<Offlining> getOfflinings() {
        List<Offlining> offlinings = new ArrayList<>();
        for (int i = 0; i < mOfflinings.size(); i++) {
            offlinings.add(mOfflinings.valueAt(i));
        }
        return offlinings;
    }

    public void actionStart() {
        sync(() -> Observable.from(list())
                .filter(offlining -> OfflineState.WAITING.value == offlining.getState())
                .map(offlining -> {
                    long id = offlining.getId();
                    Subscription subscription = mSubscriptions.get(id);
                    if (subscription != null) {
                        subscription.unsubscribe();
                    }
                    mSubscriptions.remove(id);
                    mSubscriptions.put(id, start(id));
                    return null;
                })
                .subscribe(o -> {

                }, throwable -> {
                    Logger.w(TAG, throwable);
                }));
    }

    public void actionResumeAll() {
        Observable.from(list())
                .observeOn(Schedulers.io())
                .filter(offlining -> OfflineState.resume(offlining.getState()))
                .map(offlining -> {
                    offlining.setState(OfflineState.WAITING.value);
                    return offlining;
                })
                .subscribe(o -> {
                    actionStart();
                }, throwable -> {
                    Logger.w(TAG, throwable);
                });
    }

    public void actionPauseAll() {
        Observable.from(list())
                .observeOn(Schedulers.io())
                .filter(offlining -> OfflineState.valueOf(offlining.getState()).canPause())
                .map(offlining -> {
                    pauseSync(offlining);
                    return true;
                })
                .subscribe(result -> {

                }, throwable -> {
                    Logger.w(TAG, throwable);
                });
    }

    public void pauseSync(Offlining offlining) {
        if (offlining == null) return;
        long id = offlining.getId();
        Subscription subscription = mSubscriptions.get(id);
        if (subscription != null) {
            subscription.unsubscribe();
        }
        mSubscriptions.remove(id);
        offlining.setState(OfflineState.PAUSE.value);
        save(offlining);
    }

    public void toggle(long id) {

    }

    private boolean isStart(Subscription subscription) {
        return subscription != null && !subscription.isUnsubscribed();
    }

    public long getMaxId() {
        if (mOfflinings != null && mOfflinings.size() > 0) {
            return mOfflinings.keyAt(mOfflinings.size() - 1);
        }
        return 0L;
    }

    public void put(long id, Subscription subscription) {
        mSubscriptions.put(id, subscription);
    }

    public Offlining get(long id) {
        return mOfflinings.get(id);
    }

    public List<Offlining> list() {
        List<Offlining> offlinings = new ArrayList<>();
        int size = mOfflinings.size();
        for (int i = 0; i < size; i++) {
            offlinings.add(mOfflinings.valueAt(i));
        }
        return offlinings;
    }

    public void remove(long id) {
        Observable.just(id)
                .map(oid -> {
                    Subscription subscription = mSubscriptions.get(oid);
                    if (subscription != null) {
                        subscription.unsubscribe();
                        mSubscriptions.remove(id);
                    }
                    mOfflinings.remove(oid);
                    try {
                        return mOfflineDao.deleteById(oid) == 1;
                    } catch (SQLException e) {
                        Logger.w(TAG, e);
                    }
                    return false;
                })
                .subscribeOn(Schedulers.io())
                .subscribe(success -> {
                    mCallback.remove(id, success);
                }, throwable -> {
                    Logger.w(TAG, throwable);
                });
    }

    public boolean save(long id) {
        return save(get(id));
    }

    public boolean save(Offlining offlining) {
        if (offlining != null) {
            Offline offline = new Offline(offlining);
            if (offline.getState() == OfflineState.OFFLINING.value) {
                offline.setState(OfflineState.WAITING.value);
            }
            try {
                mOfflineDao.createOrUpdate(offline);
                return true;
            } catch (SQLException ignored) {

            }
        }
        return false;
    }

    public Subscription start(long id) {
        return Observable
                .create(new Observable.OnSubscribe<Offlining>() {
                    @Override
                    public void call(Subscriber<? super Offlining> subscriber) {
                        Offlining offlining = mOfflinings.get(id);
                        Logger.d(TAG, "\n\n++++++++++++++");
                        Logger.d(TAG, "start:" + offlining);

                        if (offlining == null) {
                            subscriber.onError(new IllegalArgumentException("找不到Id对应的Offlining"));
                            return;
                        }

                        long progress = offlining.getProgress();

                        Logger.d(TAG, "progress:" + progress);

                        OkHttpClient client = new OkHttpClient();
                        Request request = new Request.Builder()
                                .url(offlining.getPath())
                                .addHeader("Range", "bytes=%d-" + progress)
                                .get()
                                .build();

                        RandomAccessFile accessFile = null;
                        try {
                            offlining.setState(OfflineState.OFFLINING.value);
                            subscriber.onNext(offlining);

                            Response response = client.newCall(request).execute();
                            ResponseBody body = response.body();

                            offlining.setTotal(Math.max(progress + body.contentLength(), offlining.getTotal()));

                            Logger.d(TAG, "offlining total=" + offlining.getTotal());

                            File file = FileUtils.obtainFile(new File(offlining.getDest()));
                            accessFile = new RandomAccessFile(file, "rw");
                            accessFile.seek(progress);

                            InputStream inputStream = response.body().byteStream();
                            int len, round = 0;
                            long millis = SystemClock.uptimeMillis();
                            byte[] buffer = new byte[BUFFER_SIZE];
                            while (!subscriber.isUnsubscribed() && (len = inputStream.read(buffer)) != -1) {
                                accessFile.write(buffer, 0, len);
                                progress += len;
                                offlining.setProgress(progress);
                                if (SystemClock.uptimeMillis() - millis > UPDATE_GAP) {
                                    millis = SystemClock.uptimeMillis();
                                    subscriber.onNext(offlining);
                                    if (round++ % 15 == 0) save(offlining);
                                }
                            }

                            if (offlining.getTotal() <= offlining.getProgress()) {
                                offlining.setState(OfflineState.DONE.value);
                            }

                            save(offlining);
                            subscriber.onNext(offlining);
                        } catch (Exception e) {
                            Logger.w(TAG, e);
                            Util.closeQuietly(accessFile);
                            offlining.setState(OfflineState.ERROR.value);
                            save(offlining);
                            subscriber.onNext(offlining);
                        }
                        subscriber.onCompleted();
                    }
                })
                .subscribeOn(mOffliningScheduler)
                .doOnUnsubscribe(() -> mCallback.update(get(id)))
                .subscribe(offlining -> mCallback.update(offlining),
                        throwable -> Logger.w(TAG, throwable),
                        ()->{

                            actionStart();
                        });
    }

    public interface OffliningCallback {
        void update(Offlining offlining);

        void update(List<Offlining> offlinings);

        void remove(long id, boolean success);

        void add(Offlining offlining);
    }
}