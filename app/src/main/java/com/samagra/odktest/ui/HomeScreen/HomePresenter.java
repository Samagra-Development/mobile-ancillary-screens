package com.samagra.odktest.ui.HomeScreen;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.view.View;

import com.samagra.odktest.MyApplication;
import com.samagra.odktest.base.BasePresenter;

import java.util.HashMap;
import java.util.List;

import javax.inject.Inject;

/**
 * The Presenter class for Home Screen. This class controls interaction between the View and Data.
 * This class <b>must</b> implement the {@link HomeMvpPresenter} and <b>must</b> be a type of {@link BasePresenter}.
 *
 * @author Pranav Sharma
 */
public class HomePresenter<V extends HomeMvpView, I extends HomeMvpInteractor> extends BasePresenter<V, I> implements HomeMvpPresenter<V, I> {


    /**
     * The injected values is provided through {@link com.samagra.odktest.di.modules.ActivityAbstractProviders}
     */
    @Inject
    public HomePresenter(I mvpInteractor) {
        super(mvpInteractor);
    }


    @Override
    public void onMyVisitClicked(View v) {
        // launchActivity(MyVisitsActivity.class);
    }

    @Override
    public void onInspectSchoolClicked(View v) {
    }

    @Override
    public void onSubmitFormClicked(View v) {

    }

    @Override
    public void onUnSubmittedFormClicked(View v) {
    }

    @Override
    public void onViewIssuesClicked(View v) {

    }

    @Override
    public void onHelplineButtonClicked(View v) {
        Intent callIntent = new Intent(Intent.ACTION_DIAL);
        callIntent.setData(Uri.parse("tel:9673464857"));
        v.getContext().startActivity(callIntent);
    }

    @Override
    public void setWelcomeText() {
        getMvpView().updateWelcomeText(getMvpInteractor().getUserName());
    }



    @Override
    public void downloadForms(HashMap<String, String> formsToBeDownloaded) {
        // formProgressBar.setProgress(0);
    }

    @Override
    public void applySettings() {
    }

    @Override
    public boolean isNetworkConnected() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getMvpView()
                .getActivityContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo != null && networkInfo.isConnected();
    }


    /**
     * Please add your tutorial id and key here.
     * @return
     */
    @Override
    public String getYoutubeAPIKey() {
        if(MyApplication.getmFirebaseRemoteConfig() != null) {
           return MyApplication.getmFirebaseRemoteConfig().getString("youtube_api_key");
        }
        return "";
    }

    @Override
    public String getTutorialVideoID() {
        if(MyApplication.getmFirebaseRemoteConfig() != null) {
            return MyApplication.getmFirebaseRemoteConfig().getString("youtube_tutorial_video_id");
        }
        return "";
    }

}
