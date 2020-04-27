package com.samagra.odktest.ui.HomeScreen;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;

import androidx.appcompat.widget.Toolbar;

import com.androidnetworking.AndroidNetworking;
import com.google.android.material.snackbar.Snackbar;
import com.samagra.ancillaryscreens.AncillaryScreensDriver;
import com.samagra.ancillaryscreens.models.AboutBundle;
import com.samagra.commons.Constants;
import com.samagra.commons.CustomEvents;
import com.samagra.commons.ExchangeObject;
import com.samagra.commons.InternetMonitor;
import com.samagra.commons.MainApplication;
import com.samagra.commons.Modules;
import com.samagra.odktest.AppConstants;
import com.samagra.odktest.R;
import com.samagra.odktest.UtilityFunctions;
import com.samagra.odktest.base.BaseActivity;

import javax.inject.Inject;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;
import timber.log.Timber;

/**
 * View part of the Home Screen. This class only handles the UI operations, all the business logic is simply
 * abstracted from this Activity. It <b>must</b> implement the {@link HomeMvpView} and extend the {@link BaseActivity}
 *
 * @author Pranav Sharma
 */
public class HomeActivity extends BaseActivity implements HomeMvpView, View.OnClickListener {

    @BindView(R.id.fill_forms)
    public LinearLayout fillFormLayout;

    @BindView(R.id.view_submitted_forms)
    public LinearLayout viewSubmittedFormLayout;

    @BindView(R.id.submit_forms)
    public LinearLayout submitFormsLayout;

    @BindView(R.id.need_help)
    public LinearLayout needHelpLayout;
    @BindView(R.id.circularProgressBar)
    public ProgressBar circularProgressBar;

    @BindView(R.id.parentHome)
    public LinearLayout parentHome;

    private PopupMenu popupMenu;
    private Disposable logoutListener = null;
    private Snackbar progressSnackbar = null;

    private Unbinder unbinder;

    @Inject
    HomePresenter<HomeMvpView, HomeMvpInteractor> homePresenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        getActivityComponent().inject(this);
        unbinder = ButterKnife.bind(this);
        homePresenter.onAttach(this);
        setupToolbar();
        setupListeners();
        checkIntent();
        homePresenter.setWelcomeText();
        homePresenter.applySettings();
        InternetMonitor.startMonitoringInternet();
    }

    @Override
    protected void onResume() {
        super.onResume();
        customizeToolbar();
        homePresenter.setWelcomeText();
    }

    private void setupListeners() {
        fillFormLayout.setOnClickListener(this);
        viewSubmittedFormLayout.setOnClickListener(this);
        submitFormsLayout.setOnClickListener(this);
        needHelpLayout.setOnClickListener(this);
    }

    private void checkIntent() {
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra("ShowSnackbar", false)) {
            if (homePresenter.isNetworkConnected())
                showSnackbar(getString(R.string.on_internet_saving_complete), Snackbar.LENGTH_SHORT);
            else
                showSnackbar(getString(R.string.no_internet_saving_complete), Snackbar.LENGTH_SHORT);
        }
    }

    @Override
    public void renderLayoutVisible() {
        parentHome.setVisibility(View.VISIBLE);
        circularProgressBar.setVisibility(View.GONE);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.fill_forms:
                homePresenter.onInspectSchoolClicked(v);
                break;
            case R.id.view_submitted_forms:
                homePresenter.onSubmitFormClicked(v);
                break;

            case R.id.submit_forms:
                homePresenter.onUnSubmittedFormClicked(v);
                break;
            case R.id.need_help:
                Bundle bundle = new Bundle();
                bundle.putString("youtube_api_key", homePresenter.getYoutubeAPIKey());
                bundle.putString("youtube_tutorial_video_id", homePresenter.getTutorialVideoID());
                AncillaryScreensDriver.launchTutorialActivity(this, bundle);
                break;
        }
    }

    @Override
    public void updateWelcomeText(String text) {

    }

    @Override
    public void showLoading(String message) {
        hideLoading();
        if (progressSnackbar == null) {
            progressSnackbar = UtilityFunctions.getSnackbarWithProgressIndicator(findViewById(android.R.id.content), getApplicationContext(), message);
        }
        progressSnackbar.setText(message);
        progressSnackbar.show();
    }

    @Override
    public void hideLoading() {
        if (progressSnackbar != null && progressSnackbar.isShownOrQueued())
            progressSnackbar.dismiss();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (logoutListener != null && !logoutListener.isDisposed()) {
            AndroidNetworking.cancel(Constants.LOGOUT_CALLS);
            logoutListener.dispose();
        }
        homePresenter.onDetach();
        unbinder.unbind();
    }

    /**
     * Only set the title and action bar here; do not make further modifications.
     * Any further modifications done to the toolbar here will be overwritten. If you wish to prevent modifications
     * from being overwritten, do them after onCreate is complete.
     * This method should be called in onCreate of your activity.
     */
    @Override
    public void setupToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        setTitle(R.string.app_name);
        setSupportActionBar(toolbar);
    }

    public void customizeToolbar() {
        Toolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationIcon(R.drawable.ic_menu_white_24dp);
        toolbar.setNavigationOnClickListener(this::initAndShowPopupMenu);
    }

    /**
     * Giving Control of the UI to XML file for better customization and easier changes
     */
    private void initAndShowPopupMenu(View v) {

        if (popupMenu == null) {
            popupMenu = new PopupMenu(HomeActivity.this, v);
            popupMenu.getMenuInflater().inflate(R.menu.home_screen_menu, popupMenu.getMenu());
            popupMenu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case R.id.about_us:
                        AncillaryScreensDriver.launchAboutActivity(this, provideAboutBundle());
                        break;
                    case R.id.profile:
                          break;
                    case R.id.tutorial_video:
                        Bundle bundle = new Bundle();
                        bundle.putString("youtube_api_key", homePresenter.getYoutubeAPIKey());
                        bundle.putString("youtube_tutorial_video_id", homePresenter.getTutorialVideoID());
                        AncillaryScreensDriver.launchTutorialActivity(this, bundle);
                        break;
                    case R.id.logout:
                        if(homePresenter.isNetworkConnected()){
                            if (logoutListener == null)
                                initializeLogoutListener();
                            AncillaryScreensDriver.performLogout(this);
                        }else{
                            showSnackbar("It seems you are offline. Logout cannot happen in offline conditions.", 3000);
                        }
                        break;
                }
                return true;
            });
        }
        popupMenu.show();
    }

    /**
     * Provides with a {@link AboutBundle} object that is used to further configure
     * the UI for {@link com.samagra.ancillaryscreens.screens.about.AboutActivity}
     */
    private AboutBundle provideAboutBundle() {
        return new AboutBundle(
                "About Us",
                AppConstants.ABOUT_WEBSITE_LINK,
                AppConstants.ABOOUT_FORUM_LINK,
                R.drawable.app_logo,
                R.string.about_us_title,
                R.string.about_us_summary);
    }

    /**
     * This function subsribe to the {@link com.samagra.commons.RxBus} to listen for the Logout related events
     * and update the UI accordingly. The events being subscribed to are {@link com.samagra.commons.CustomEvents#LOGOUT_COMPLETED}
     * and {@link com.samagra.commons.CustomEvents#LOGOUT_INITIATED}
     *
     * @see com.samagra.commons.CustomEvents
     */
    @Override
    public void initializeLogoutListener() {
        logoutListener = ((MainApplication) (getApplicationContext()))
                .getEventBus()
                .toObservable()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(o -> {
                    Timber.i("Received event Logout");
                    if (o instanceof ExchangeObject.EventExchangeObject) {
                        ExchangeObject.EventExchangeObject eventExchangeObject = (ExchangeObject.EventExchangeObject) o;
                        if (eventExchangeObject.to == Modules.MAIN_APP && eventExchangeObject.from == Modules.ANCILLARY_SCREENS) {
                            if (eventExchangeObject.customEvents == CustomEvents.LOGOUT_COMPLETED) {
                                hideLoading();
                                logoutListener.dispose();
                            } else if (eventExchangeObject.customEvents == CustomEvents.LOGOUT_INITIATED) {
                                showLoading("Logging you out...");
                            }
                        }
                    }
                }, Timber::e);
    }

    @Override
    public void showFormsStillDownloading() {
        showSnackbar("Forms are downloading, please wait..", Snackbar.LENGTH_SHORT);
    }

}
