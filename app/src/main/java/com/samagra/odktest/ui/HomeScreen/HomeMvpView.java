package com.samagra.odktest.ui.HomeScreen;

import com.samagra.odktest.base.MvpView;

/**
 * The view interface 'contract' for the Home Screen. This defines all the functionality required by the
 * Presenter for the view as well as for enforcing certain structure in the Views.
 * The {@link HomeActivity} <b>must</b> implement this interface. This way, the business logic behind the screen
 * can remain unaffected.
 *
 * @author Pranav Sharma
 */
public interface HomeMvpView extends MvpView {

    void updateWelcomeText(String text);

    void showLoading(String message);

    void hideLoading();

    /**
     * This function subsribe to the {@link com.samagra.commons.RxBus} to listen for the Logout related events
     * and update the UI accordingly. The events being subscribed to are {@link com.samagra.commons.CustomEvents#LOGOUT_COMPLETED}
     * and {@link com.samagra.commons.CustomEvents#LOGOUT_INITIATED}
     *
     * @see com.samagra.commons.CustomEvents
     */
    void initializeLogoutListener();

    void showFormsStillDownloading();

    void renderLayoutVisible();
}
