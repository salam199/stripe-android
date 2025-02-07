package com.stripe.android;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.Size;
import android.support.annotation.VisibleForTesting;

import com.stripe.android.exception.APIConnectionException;
import com.stripe.android.exception.APIException;
import com.stripe.android.exception.AuthenticationException;
import com.stripe.android.exception.CardException;
import com.stripe.android.exception.InvalidRequestException;
import com.stripe.android.exception.StripeException;
import com.stripe.android.model.AccountParams;
import com.stripe.android.model.BankAccount;
import com.stripe.android.model.Card;
import com.stripe.android.model.PaymentIntent;
import com.stripe.android.model.PaymentIntentParams;
import com.stripe.android.model.PaymentMethod;
import com.stripe.android.model.PaymentMethodCreateParams;
import com.stripe.android.model.SetupIntent;
import com.stripe.android.model.SetupIntentParams;
import com.stripe.android.model.Source;
import com.stripe.android.model.SourceParams;
import com.stripe.android.model.Token;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

import static com.stripe.android.StripeNetworkUtils.createPersonalIdTokenParams;
import static com.stripe.android.StripeNetworkUtils.createUpdateCvcTokenParams;

/**
 * Entry-point to the Stripe SDK that handles
 * - {@link Token} creation from charges, {@link Card}, and accounts
 * - {@link PaymentMethod} creation
 * - {@link PaymentIntent} retrieval and confirmation
 */
@SuppressWarnings("WeakerAccess")
public class Stripe {
    @Nullable private static AppInfo sAppInfo;

    @NonNull private final StripeApiHandler mApiHandler;
    @NonNull private final StripeNetworkUtils mStripeNetworkUtils;
    @NonNull private final PaymentController mPaymentController;
    @NonNull private final TokenCreator mTokenCreator;
    @NonNull private final ApiKeyValidator mApiKeyValidator;
    private String mDefaultPublishableKey;
    @Nullable private String mStripeAccount;

    /**
     * A constructor with only context, to set the key later.
     *
     * @param context {@link Context} for resolving resources
     */
    public Stripe(@NonNull Context context) {
        this(context, new StripeApiHandler(context, sAppInfo), new StripeNetworkUtils(context),
                null);
    }

    /**
     * Constructor with publishable key.
     *
     * @param context {@link Context} for resolving resources
     * @param publishableKey the client's publishable key
     */
    public Stripe(@NonNull Context context, @NonNull String publishableKey) {
        this(context, new StripeApiHandler(context, sAppInfo), new StripeNetworkUtils(context),
                ApiKeyValidator.get().requireValid(publishableKey));
    }

    Stripe(@NonNull Context context, @NonNull final StripeApiHandler apiHandler,
                   @NonNull StripeNetworkUtils stripeNetworkUtils,
                   @Nullable String publishableKey) {
        this(apiHandler, stripeNetworkUtils,
                new PaymentController(context, apiHandler), publishableKey);
    }

    Stripe(@NonNull final StripeApiHandler apiHandler,
           @NonNull StripeNetworkUtils stripeNetworkUtils,
           @NonNull PaymentController paymentController,
           @Nullable String publishableKey) {
        this(apiHandler, stripeNetworkUtils, paymentController, publishableKey,
                new TokenCreator() {
                    @Override
                    public void create(
                            @NonNull final Map<String, Object> tokenParams,
                            @NonNull final ApiRequest.Options options,
                            @NonNull @Token.TokenType final String tokenType,
                            @Nullable final Executor executor,
                            @NonNull final TokenCallback callback) {
                        executeTask(executor,
                                new CreateTokenTask(apiHandler, tokenParams, options,
                                        tokenType, callback));
                    }
                });
    }

    @VisibleForTesting
    Stripe(@NonNull StripeApiHandler apiHandler,
           @NonNull StripeNetworkUtils stripeNetworkUtils,
           @NonNull PaymentController paymentController,
           @Nullable String publishableKey,
           @NonNull TokenCreator tokenCreator) {
        mApiKeyValidator = new ApiKeyValidator();
        mApiHandler = apiHandler;
        mStripeNetworkUtils = stripeNetworkUtils;
        mPaymentController = paymentController;
        mTokenCreator = tokenCreator;
        mDefaultPublishableKey = publishableKey != null ?
                mApiKeyValidator.requireValid(publishableKey) : null;
    }

    /**
     * Setter for identifying your plug-in or library.
     *
     * See <a href="https://stripe.com/docs/building-plugins#setappinfo">
     *     https://stripe.com/docs/building-plugins#setappinfo</a>
     */
    public static void setAppInfo(@Nullable AppInfo appInfo) {
        sAppInfo = appInfo;
    }

    @Nullable
    static AppInfo getAppInfo() {
        return sAppInfo;
    }

    /**
     * Confirm and, if necessary, authenticate a {@link SetupIntent}.
     *
     * @param activity the {@link Activity} that is launching the payment authentication flow
     * @param setupIntentParams {@link SetupIntentParams} used to confirm the {@link SetupIntent}
     */
    public void confirmSetupIntent(@NonNull Activity activity,
                                   @NonNull SetupIntentParams setupIntentParams,
                                   @NonNull String publishableKey) {
        mPaymentController.startConfirmAndAuth(this, activity,
                setupIntentParams, publishableKey);
    }

    /**
     * See {@link #confirmSetupIntent(Activity, SetupIntentParams, String)}}
     */
    public void confirmSetupIntent(@NonNull Activity activity,
                                   @NonNull SetupIntentParams setupIntentParams) {
        confirmSetupIntent(activity, setupIntentParams, mDefaultPublishableKey);
    }

    /**
     * Confirm and, if necessary, authenticate a {@link PaymentIntent}. Used for <a href=
     * "https://stripe.com/docs/payments/payment-intents/quickstart#automatic-confirmation-flow">
     * automatic confirmation</a> flow.
     *
     * @param activity the {@link Activity} that is launching the payment authentication flow
     * @param confirmPaymentIntentParams {@link PaymentIntentParams} used to confirm the
     *                                   {@link PaymentIntent}
     */
    public void confirmPayment(@NonNull Activity activity,
                               @NonNull PaymentIntentParams confirmPaymentIntentParams,
                               @NonNull String publishableKey) {
        mPaymentController.startConfirmAndAuth(this, activity,
                confirmPaymentIntentParams, publishableKey);
    }

    /**
     * See {@link #confirmPayment(Activity, PaymentIntentParams, String)}}
     */
    public void confirmPayment(@NonNull Activity activity,
                               @NonNull PaymentIntentParams confirmPaymentIntentParams) {
        confirmPayment(activity, confirmPaymentIntentParams, mDefaultPublishableKey);
    }

    /**
     * Authenticate a {@link PaymentIntent}. Used for <a href=
     * "https://stripe.com/docs/payments/payment-intents/quickstart#manual-confirmation-flow">
     * manual confirmation</a> flow.
     *
     * @param activity the {@link Activity} that is launching the payment authentication flow
     * @param paymentIntent a confirmed {@link PaymentIntent} object
     */
    public void authenticatePayment(@NonNull Activity activity,
                                    @NonNull PaymentIntent paymentIntent,
                                    @NonNull String publishableKey) {
        mPaymentController.startAuth(activity, paymentIntent, publishableKey);
    }

    /**
     * See {@link #authenticatePayment(Activity, PaymentIntent, String)}}
     */
    public void authenticatePayment(@NonNull Activity activity,
                                    @NonNull PaymentIntent paymentIntent) {
        authenticatePayment(activity, paymentIntent, mDefaultPublishableKey);
    }

    /**
     * Should be called via {@link Activity#onActivityResult(int, int, Intent)}} to handle the
     * result of a PaymentIntent automatic confirmation
     * (see {@link #confirmPayment(Activity, PaymentIntentParams, String)}) or manual confirmation
     * (see {@link #authenticatePayment(Activity, PaymentIntent, String)}})
     */
    public boolean onPaymentResult(int requestCode, int resultCode, @Nullable Intent data,
                                   @NonNull String publishableKey,
                                   @NonNull ApiResultCallback<PaymentIntentResult> callback) {
        if (data != null &&
                mPaymentController.shouldHandlePaymentResult(requestCode, resultCode, data)) {
            mPaymentController.handlePaymentResult(this, data, publishableKey, callback);
            return true;
        }

        return false;
    }

    /**
     * See {@link #onPaymentResult(int, int, Intent, String, ApiResultCallback)}
     */
    public boolean onPaymentResult(
            int requestCode, int resultCode, @Nullable Intent data,
            @NonNull ApiResultCallback<PaymentIntentResult> callback) {
        return onPaymentResult(requestCode, resultCode, data, mDefaultPublishableKey, callback);
    }

    /**
     * Should be called via {@link Activity#onActivityResult(int, int, Intent)}} to handle the
     * result of a SetupIntent confirmation
     * (see {@link #confirmSetupIntent(Activity, SetupIntentParams)})
     */
    public boolean onSetupResult(int requestCode, int resultCode, @Nullable Intent data,
                                   @NonNull String publishableKey,
                                   @NonNull ApiResultCallback<SetupIntentResult> callback) {
        if (data != null &&
                mPaymentController.shouldHandleSetupResult(requestCode, resultCode, data)) {
            mPaymentController.handleSetupResult(this, data, publishableKey, callback);
            return true;
        }

        return false;
    }

    /**
     * See {@link #onSetupResult(int, int, Intent, String, ApiResultCallback)}
     */
    public boolean onSetupResult(int requestCode, int resultCode, @Nullable Intent data,
            @NonNull ApiResultCallback<SetupIntentResult> callback) {
        return onSetupResult(requestCode, resultCode, data, mDefaultPublishableKey, callback);
    }

    /**
     * The simplest way to create a {@link BankAccount} token. This runs on the default
     * {@link Executor} and with the currently set {@link #mDefaultPublishableKey}.
     *
     * @param bankAccount the {@link BankAccount} used to create this token
     * @param callback a {@link TokenCallback} to receive either the token or an error
     */
    public void createBankAccountToken(
            @NonNull final BankAccount bankAccount,
            @NonNull final TokenCallback callback) {
        createBankAccountToken(bankAccount, mDefaultPublishableKey, null, callback);
    }

    /**
     * Call to create a {@link Token} for a {@link BankAccount} with the publishable key and
     * {@link Executor} specified.
     *
     * @param bankAccount the {@link BankAccount} for which to create a {@link Token}
     * @param publishableKey the publishable key to use
     * @param executor an {@link Executor} to run this operation on. If null, this is run on a
     *         default non-ui executor
     * @param callback a {@link TokenCallback} to receive the result or error message
     */
    public void createBankAccountToken(
            @NonNull final BankAccount bankAccount,
            @NonNull @Size(min = 1) final String publishableKey,
            @Nullable final Executor executor,
            @NonNull final TokenCallback callback) {
        Objects.requireNonNull(bankAccount,

                    "Required parameter: 'bankAccount' is requred to create a token");

        createTokenFromParams(
                mStripeNetworkUtils.createBankAccountTokenParams(bankAccount),
                publishableKey,
                Token.TYPE_BANK_ACCOUNT,
                executor,
                callback);
    }

    /**
     * The simplest way to create a PII token. This runs on the default
     * {@link Executor} and with the currently set {@link #mDefaultPublishableKey}.
     *
     * @param personalId the personal id used to create this token
     * @param callback a {@link TokenCallback} to receive either the token or an error
     */
    public void createPiiToken(
            @NonNull final String personalId,
            @NonNull final TokenCallback callback) {
        createPiiToken(personalId, mDefaultPublishableKey, null, callback);
    }

    /**
     * Call to create a {@link Token} for PII with the publishable key and
     * {@link Executor} specified.
     *
     * @param personalId the personal id used to create this token
     * @param publishableKey the publishable key to use
     * @param executor an {@link Executor} to run this operation on. If null, this is run on a
     *         default non-ui executor
     * @param callback a {@link TokenCallback} to receive the result or error message
     */
    public void createPiiToken(
            @NonNull final String personalId,
            @NonNull @Size(min = 1) final String publishableKey,
            @Nullable final Executor executor,
            @NonNull final TokenCallback callback) {
        createTokenFromParams(
                createPersonalIdTokenParams(personalId),
                publishableKey,
                Token.TYPE_PII,
                executor,
                callback);
    }

    /**
     * Blocking method to create a {@link Token} for a {@link BankAccount}. Do not call this on
     * the UI thread or your app will crash.
     *
     * This method uses the default publishable key for this {@link Stripe} instance.
     *
     * @param bankAccount the {@link Card} to use for this token
     * @return a {@link Token} that can be used for this {@link BankAccount}
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws CardException should not be thrown with this type of token, but is theoretically
     *         possible given the underlying methods called
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers
     */
    @Nullable
    public Token createBankAccountTokenSynchronous(@NonNull final BankAccount bankAccount)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            CardException,
            APIException {
        return createBankAccountTokenSynchronous(bankAccount, mDefaultPublishableKey);
    }

    /**
     * Blocking method to create a {@link Token} using a {@link BankAccount}. Do not call this on
     * the UI thread or your app will crash.
     *
     * @param bankAccount the {@link BankAccount} to use for this token
     * @param publishableKey the publishable key to use with this request
     * @return a {@link Token} that can be used for this {@link BankAccount}
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws CardException should not be thrown with this type of token, but is theoretically
     *         possible given the underlying methods called
     * @throws APIException any other type of problem
     */
    @Nullable
    public Token createBankAccountTokenSynchronous(@NonNull final BankAccount bankAccount,
                                                   @NonNull String publishableKey)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            CardException,
            APIException {
        return mApiHandler.createToken(
                mStripeNetworkUtils.createBankAccountTokenParams(bankAccount),
                ApiRequest.Options.create(publishableKey, mStripeAccount),
                Token.TYPE_BANK_ACCOUNT
        );
    }

    /**
     * The simplest way to create a CVC update token. This runs on the default
     * {@link Executor} and with the currently set {@link #mDefaultPublishableKey}.
     *
     * @param cvc the CVC used to create this token
     * @param callback a {@link TokenCallback} to receive either the token or an error
     */
    public void createCvcUpdateToken(
            @NonNull @Size(min = 3, max = 4) final String cvc,
            @NonNull final TokenCallback callback) {
        createCvcUpdateToken(cvc, mDefaultPublishableKey, null, callback);
    }

    /**
     * Call to create a {@link Token} for CVC with the publishable key and
     * {@link Executor} specified.
     *
     * @param cvc the CVC used to create this token
     * @param publishableKey the publishable key to use
     * @param executor an {@link Executor} to run this operation on. If null, this is run on a
     *         default non-ui executor
     * @param callback a {@link TokenCallback} to receive the result or error message
     */
    public void createCvcUpdateToken(
            @NonNull @Size(min = 3, max = 4) final String cvc,
            @NonNull @Size(min = 1) final String publishableKey,
            @Nullable final Executor executor,
            @NonNull final TokenCallback callback) {
        createTokenFromParams(
                createUpdateCvcTokenParams(cvc),
                publishableKey,
                Token.TYPE_CVC_UPDATE,
                executor,
                callback);
    }

    /**
     * Create a {@link Source} using an {@link AsyncTask} on the default {@link Executor} with a
     * publishable api key that has already been set on this {@link Stripe} instance.
     *
     * @param sourceParams the {@link SourceParams} to be used
     * @param callback a {@link SourceCallback} to receive a result or an error message
     */
    public void createSource(@NonNull SourceParams sourceParams, @NonNull SourceCallback callback) {
        createSource(sourceParams, callback, mDefaultPublishableKey, null);
    }

    /**
     * Create a {@link Source} using an {@link AsyncTask}.
     *
     * @param sourceParams the {@link SourceParams} to be used
     * @param callback a {@link SourceCallback} to receive a result or an error message
     * @param publishableKey the publishable api key to be used
     * @param executor an {@link Executor} on which to execute the task, or {@link null} for default
     */
    public void createSource(
            @NonNull SourceParams sourceParams,
            @NonNull SourceCallback callback,
            @NonNull String publishableKey,
            @Nullable Executor executor) {
        executeTask(executor,
                new CreateSourceTask(mApiHandler, sourceParams, publishableKey, mStripeAccount,
                        callback));
    }

    /**
     * Create a {@link PaymentMethod} using an {@link AsyncTask} on the default {@link Executor}
     * with a publishable api key that has already been set on this {@link Stripe} instance.
     *
     * @param paymentMethodCreateParams the {@link PaymentMethodCreateParams} to be used
     * @param callback a {@link ApiResultCallback<PaymentMethod>} to receive a result or an error
     *         message
     */
    public void createPaymentMethod(@NonNull PaymentMethodCreateParams paymentMethodCreateParams,
                                    @NonNull ApiResultCallback<PaymentMethod> callback) {
        createPaymentMethod(paymentMethodCreateParams, callback, mDefaultPublishableKey, null);
    }

    /**
     * Create a {@link PaymentMethod} using an {@link AsyncTask}.
     *
     * @param paymentMethodCreateParams the {@link PaymentMethodCreateParams} to be used
     * @param callback a {@link ApiResultCallback<PaymentMethod>} to receive a result or an error
     *         message
     * @param publishableKey the publishable api key to be used
     * @param executor an {@link Executor} on which to execute the task, or {@link null} for default
     */
    public void createPaymentMethod(
            @NonNull PaymentMethodCreateParams paymentMethodCreateParams,
            @NonNull ApiResultCallback<PaymentMethod> callback,
            @NonNull String publishableKey,
            @Nullable Executor executor) {
        executeTask(executor, new CreatePaymentMethodTask(mApiHandler, paymentMethodCreateParams,
                publishableKey, mStripeAccount, callback));
    }

    /**
     * The simplest way to create a token, using a {@link Card} and {@link TokenCallback}. This
     * runs on the default {@link Executor} and with the
     * currently set {@link #mDefaultPublishableKey}.
     *
     * @param card the {@link Card} used to create this payment token
     * @param callback a {@link TokenCallback} to receive either the token or an error
     */
    public void createToken(@NonNull final Card card, @NonNull final TokenCallback callback) {
        createToken(card, mDefaultPublishableKey, callback);
    }

    /**
     * Call to create a {@link Token} with a specific public key.
     *
     * @param card the {@link Card} used for this transaction
     * @param publishableKey the public key used for this transaction
     * @param callback a {@link TokenCallback} to receive the result of this operation
     */
    public void createToken(
            @NonNull final Card card,
            @NonNull final String publishableKey,
            @NonNull final TokenCallback callback) {
        createToken(card, publishableKey, null, callback);
    }

    /**
     * Call to create a {@link Token} on a specific {@link Executor}.
     *
     * @param card the {@link Card} to use for this token creation
     * @param executor An {@link Executor} on which to run this operation. If you don't wish to
     *         specify an executor, use one of the other createTokenFromParams methods.
     * @param callback a {@link TokenCallback} to receive the result of this operation
     */
    public void createToken(
            @NonNull final Card card,
            @NonNull final Executor executor,
            @NonNull final TokenCallback callback) {
        createToken(card, mDefaultPublishableKey, executor, callback);
    }

    /**
     * Call to create a {@link Token} with the publishable key and {@link Executor} specified.
     *
     * @param card the {@link Card} used for this token
     * @param publishableKey the publishable key to use
     * @param executor an {@link Executor} to run this operation on. If null, this is run on a
     *         default non-ui executor
     * @param callback a {@link TokenCallback} to receive the result or error message
     */
    public void createToken(
            @NonNull final Card card,
            @NonNull @Size(min = 1) final String publishableKey,
            @Nullable final Executor executor,
            @NonNull final TokenCallback callback) {
        Objects.requireNonNull(card,
                "Required Parameter: 'card' is required to create a token");

        createTokenFromParams(
                mStripeNetworkUtils.createCardTokenParams(card),
                publishableKey,
                Token.TYPE_CARD,
                executor,
                callback);
    }

    /**
     * Blocking method to create a {@link Source} object using this object's
     * {@link Stripe#mDefaultPublishableKey key}.
     *
     * Do not call this on the UI thread or your app will crash.
     *
     * @param params a set of {@link SourceParams} with which to create the source
     * @return a {@link Source}, or {@code null} if a problem occurred
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers
     */
    @Nullable
    public Source createSourceSynchronous(@NonNull SourceParams params)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return createSourceSynchronous(params, mDefaultPublishableKey);
    }

    /**
     * Blocking method to create a {@link Source} object.
     * Do not call this on the UI thread or your app will crash.
     *
     * @param params a set of {@link SourceParams} with which to create the source
     * @param publishableKey a publishable API key to use
     * @return a {@link Source}, or {@code null} if a problem occurred
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers
     */
    @Nullable
    public Source createSourceSynchronous(
            @NonNull SourceParams params,
            @NonNull String publishableKey)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return mApiHandler.createSource(params,
                ApiRequest.Options.create(publishableKey, mStripeAccount));
    }

    /**
     * Blocking method to retrieve a {@link PaymentIntent} object.
     * Do not call this on the UI thread or your app will crash.
     *
     * @param paymentIntentParams a set of params with which to retrieve the Payment Intent
     * @param publishableKey a publishable API key to use
     * @return a {@link PaymentIntent} or {@code null} if a problem occurred
     */
    @Nullable
    public PaymentIntent retrievePaymentIntentSynchronous(
            @NonNull PaymentIntentParams paymentIntentParams,
            @NonNull String publishableKey) throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return mApiHandler.retrievePaymentIntent(
                paymentIntentParams,
                ApiRequest.Options.create(publishableKey, mStripeAccount)
        );
    }

    /**
     * See {@link #retrievePaymentIntentSynchronous(PaymentIntentParams, String)}
     */
    @Nullable
    public PaymentIntent retrievePaymentIntentSynchronous(
            @NonNull PaymentIntentParams paymentIntentParams)
            throws APIException, AuthenticationException, InvalidRequestException,
            APIConnectionException {
        return retrievePaymentIntentSynchronous(paymentIntentParams, mDefaultPublishableKey);
    }

    /**
     * Blocking method to confirm a {@link PaymentIntent} object.
     * Do not call this on the UI thread or your app will crash.
     *
     * @param paymentIntentParams a set of params with which to confirm the Payment Intent
     * @param publishableKey a publishable API key to use
     * @return a {@link PaymentIntent} or {@code null} if a problem occurred
     */
    @Nullable
    public PaymentIntent confirmPaymentIntentSynchronous(
            @NonNull PaymentIntentParams paymentIntentParams,
            @NonNull String publishableKey) throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return mApiHandler.confirmPaymentIntent(
                paymentIntentParams,
                ApiRequest.Options.create(publishableKey, mStripeAccount)
        );
    }

    /**
     * Blocking method to retrieve a {@link SetupIntent} object.
     * Do not call this on the UI thread or your app will crash.
     *
     * @param setupIntentParams a set of params with which to retrieve the Setup Intent
     * @param publishableKey a publishable API key to use
     * @return a {@link SetupIntent} or {@code null} if a problem occurred
     */
    @Nullable
    public SetupIntent retrieveSetupIntentSynchronous(
            @NonNull SetupIntentParams setupIntentParams,
            @NonNull String publishableKey) throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return mApiHandler.retrieveSetupIntent(
                setupIntentParams,
                ApiRequest.Options.create(publishableKey, mStripeAccount)
        );
    }

    /**
     * See {@link #retrieveSetupIntentSynchronous(SetupIntentParams, String)}
     */
    @Nullable
    public SetupIntent retrieveSetupIntentSynchronous(
            @NonNull SetupIntentParams setupIntentParams)
            throws APIException, AuthenticationException, InvalidRequestException,
            APIConnectionException {
        return retrieveSetupIntentSynchronous(setupIntentParams, mDefaultPublishableKey);
    }

    /**
     * Blocking method to confirm a {@link SetupIntent} object.
     * Do not call this on the UI thread or your app will crash.
     *
     * @param setupIntentParams a set of params with which to confirm the Setup Intent
     * @param publishableKey a publishable API key to use
     * @return a {@link SetupIntent} or {@code null} if a problem occurred
     */
    @Nullable
    public SetupIntent confirmSetupIntentSynchronous(
            @NonNull SetupIntentParams setupIntentParams,
            @NonNull String publishableKey) throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return mApiHandler.confirmSetupIntent(
                setupIntentParams,
                ApiRequest.Options.create(publishableKey, mStripeAccount)
        );
    }

    /**
     * Blocking method to create a {@link PaymentMethod} object.
     * Do not call this on the UI thread or your app will crash.
     *
     * @param paymentMethodCreateParams params with which to create the PaymentMethod
     * @param publishableKey a publishable API key to use
     * @return a {@link PaymentMethod} or {@code null} if a problem occurred
     */
    @Nullable
    public PaymentMethod createPaymentMethodSynchronous(
            @NonNull PaymentMethodCreateParams paymentMethodCreateParams,
            @NonNull String publishableKey)
            throws AuthenticationException, InvalidRequestException, APIConnectionException,
            APIException {
        return mApiHandler.createPaymentMethod(paymentMethodCreateParams,
                ApiRequest.Options.create(publishableKey, mStripeAccount));
    }

    /**
     * See {@link #createPaymentMethodSynchronous(PaymentMethodCreateParams, String)}
     */
    @Nullable
    public PaymentMethod createPaymentMethodSynchronous(
            @NonNull PaymentMethodCreateParams paymentMethodCreateParams)
            throws APIException, AuthenticationException, InvalidRequestException,
            APIConnectionException {
        return createPaymentMethodSynchronous(paymentMethodCreateParams, mDefaultPublishableKey);
    }

    /**
     * Blocking method to create a {@link Token}. Do not call this on the UI thread or your app
     * will crash. This method uses the default publishable key for this {@link Stripe} instance.
     *
     * @param card the {@link Card} to use for this token
     * @return a {@link Token} that can be used for this card
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws CardException the card cannot be charged for some reason
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers
     */
    @Nullable
    public Token createTokenSynchronous(@NonNull final Card card)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            CardException,
            APIException {
        return createTokenSynchronous(card, mDefaultPublishableKey);
    }

    /**
     * Blocking method to create a {@link Token}. Do not call this on the UI thread or your app
     * will crash.
     *
     * @param card the {@link Card} to use for this token
     * @param publishableKey the publishable key to use with this request
     * @return a {@link Token} that can be used for this card
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Token createTokenSynchronous(@NonNull final Card card, @NonNull String publishableKey)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            CardException,
            APIException {
        return mApiHandler.createToken(
                mStripeNetworkUtils.createCardTokenParams(card),
                ApiRequest.Options.create(publishableKey, mStripeAccount),
                Token.TYPE_CARD
        );
    }

    /**
     * Blocking method to create a {@link Token} for PII. Do not call this on the UI thread
     * or your app will crash. The method uses the currently set {@link #mDefaultPublishableKey}.
     *
     * @param personalId the personal ID to use for this token
     * @return a {@link Token} that can be used for this card
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Token createPiiTokenSynchronous(@NonNull String personalId)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            CardException,
            APIException {
        return createPiiTokenSynchronous(personalId, mDefaultPublishableKey);
    }

    /**
     * Blocking method to create a {@link Token} for PII. Do not call this on the UI thread
     * or your app will crash.
     *
     * @param personalId the personal ID to use for this token
     * @param publishableKey the publishable key to use with this request
     * @return a {@link Token} that can be used for this card
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Token createPiiTokenSynchronous(@NonNull String personalId,
                                           @NonNull String publishableKey)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            CardException,
            APIException {
        return mApiHandler.createToken(
                createPersonalIdTokenParams(personalId),
                ApiRequest.Options.create(publishableKey, mStripeAccount),
                Token.TYPE_PII
        );
    }

    /**
     * Blocking method to create a {@link Token} for CVC updating. Do not call this on the UI thread
     * or your app will crash. The method uses the currently set {@link #mDefaultPublishableKey}.
     *
     * @param cvc the CVC to use for this token
     * @return a {@link Token} that can be used for this card
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Token createCvcUpdateTokenSynchronous(@NonNull String cvc)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            CardException,
            APIException {
        return createCvcUpdateTokenSynchronous(cvc, mDefaultPublishableKey);
    }

    /**
     * Blocking method to create a {@link Token} for CVC updating. Do not call this on the UI thread
     * or your app will crash.
     *
     * @param cvc the CVC to use for this token
     * @param publishableKey the publishable key to use with this request
     * @return a {@link Token} that can be used for this card
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Token createCvcUpdateTokenSynchronous(@NonNull String cvc,
                                                 @NonNull String publishableKey)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            CardException,
            APIException {
        return mApiHandler.createToken(
                createUpdateCvcTokenParams(cvc),
                ApiRequest.Options.create(publishableKey, mStripeAccount),
                Token.TYPE_CVC_UPDATE
        );
    }

    /**
     * Blocking method to create a {@link Token} for a Connect Account. Do not call this on the UI
     * thread or your app will crash. The method uses the currently set
     * {@link #mDefaultPublishableKey}.
     *
     * @param accountParams params to use for this token.
     * @return a {@link Token} that can be used for this account.
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Token createAccountTokenSynchronous(@NonNull final AccountParams accountParams)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return createAccountTokenSynchronous(accountParams, mDefaultPublishableKey);
    }

    /**
     * Blocking method to create a {@link Token} for a Connect Account. Do not call this on the UI
     * thread.
     *
     * @param accountParams params to use for this token.
     * @param publishableKey the publishable key to use with this request. If null is passed in as
     *         the publishable key, we will use the default publishable key.
     * @return a {@link Token} that can be used for this account.
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Token createAccountTokenSynchronous(
            @NonNull final AccountParams accountParams,
            @NonNull String publishableKey)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        try {
            return mApiHandler.createToken(
                    accountParams.toParamMap(),
                    ApiRequest.Options.create(publishableKey, mStripeAccount),
                    Token.TYPE_ACCOUNT
            );
        } catch (CardException exception) {
            // Should never occur. CardException is only for card related requests.
        }
        return null;
    }

    /**
     * Retrieve an existing {@link Source} from the Stripe API. Note that this is a
     * synchronous method, and cannot be called on the main thread. Doing so will cause your app
     * to crash. This method uses the default publishable key for this {@link Stripe} instance.
     *
     * @param sourceId the {@link Source#getId()} field of the desired Source object
     * @param clientSecret the {@link Source#getClientSecret()} field of the desired Source object
     * @return a {@link Source} if one could be found based on the input params, or {@code null} if
     *         no such Source could be found.
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Source retrieveSourceSynchronous(
            @NonNull @Size(min = 1) String sourceId,
            @NonNull @Size(min = 1) String clientSecret)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return retrieveSourceSynchronous(sourceId, clientSecret, mDefaultPublishableKey);
    }

    /**
     * Retrieve an existing {@link Source} from the Stripe API. Note that this is a
     * synchronous method, and cannot be called on the main thread. Doing so will cause your app
     * to crash.
     *
     * @param sourceId the {@link Source#getId()} field of the desired Source object
     * @param clientSecret the {@link Source#getClientSecret()} field of the desired Source object
     * @param publishableKey a publishable API key to use
     * @return a {@link Source} if one could be found based on the input params, or {@code null} if
     *         no such Source could be found.
     * @throws AuthenticationException failure to properly authenticate yourself (check your key)
     * @throws InvalidRequestException your request has invalid parameters
     * @throws APIConnectionException failure to connect to Stripe's API
     * @throws APIException any other type of problem (for instance, a temporary issue with
     *         Stripe's servers)
     */
    @Nullable
    public Source retrieveSourceSynchronous(
            @NonNull @Size(min = 1) String sourceId,
            @NonNull @Size(min = 1) String clientSecret,
            @NonNull String publishableKey)
            throws AuthenticationException,
            InvalidRequestException,
            APIConnectionException,
            APIException {
        return mApiHandler.retrieveSource(sourceId, clientSecret,
                ApiRequest.Options.create(publishableKey, mStripeAccount));
    }

    /**
     * Set the default publishable key to use with this {@link Stripe} instance.
     *
     * @param publishableKey the key to be set
     *
     * @deprecated use {@link #Stripe(Context, String)}
     */
    @Deprecated
    public void setDefaultPublishableKey(@NonNull @Size(min = 1) String publishableKey) {
        mDefaultPublishableKey = mApiKeyValidator.requireValid(publishableKey);
    }

    /**
     * Set the Stripe Connect account to use with this Stripe instance.
     *
     * @param stripeAccount the account ID to be set
     * @see <a href=https://stripe.com/docs/connect/authentication#authentication-via-the-stripe-account-header>
     *         Authentication via the stripe account header</a>
     */
    public void setStripeAccount(@NonNull @Size(min = 1) String stripeAccount) {
        mStripeAccount = stripeAccount;
    }

    private void createTokenFromParams(
            @NonNull final Map<String, Object> tokenParams,
            @NonNull @Size(min = 1) final String publishableKey,
            @NonNull @Token.TokenType final String tokenType,
            @Nullable final Executor executor,
            @NonNull final TokenCallback callback) {
        Objects.requireNonNull(callback,
                    "Required Parameter: 'callback' is required to use the created " +
                            "token and handle errors");
        mTokenCreator.create(
                tokenParams,
                ApiRequest.Options.create(publishableKey, mStripeAccount),
                tokenType,
                executor, callback);
    }

    private static void executeTask(@Nullable Executor executor,
                             @NonNull AsyncTask<Void, Void, ?> task) {
        if (executor != null) {
            task.executeOnExecutor(executor);
        } else {
            task.execute();
        }
    }

    @VisibleForTesting
    interface TokenCreator {
        void create(@NonNull Map<String, Object> params,
                    @NonNull ApiRequest.Options options,
                    @NonNull @Token.TokenType String tokenType,
                    @Nullable Executor executor,
                    @NonNull TokenCallback callback);
    }

    private static class CreateSourceTask extends ApiOperation<Source> {
        @NonNull private final StripeApiHandler mApiHandler;
        @NonNull private final SourceParams mSourceParams;
        @NonNull private final ApiRequest.Options mOptions;

        CreateSourceTask(@NonNull StripeApiHandler apiHandler,
                         @NonNull SourceParams sourceParams,
                         @NonNull String publishableKey,
                         @Nullable String stripeAccount,
                         @NonNull SourceCallback callback) {
            super(callback);
            mApiHandler = apiHandler;
            mSourceParams = sourceParams;
            mOptions = ApiRequest.Options.create(publishableKey, stripeAccount);
        }

        @Nullable
        @Override
        Source getResult() throws StripeException {
                return mApiHandler.createSource(mSourceParams, mOptions);
        }
    }

    private static class CreatePaymentMethodTask extends ApiOperation<PaymentMethod> {
        @NonNull private final StripeApiHandler mApiHandler;
        @NonNull private final PaymentMethodCreateParams mPaymentMethodCreateParams;
        @NonNull private final ApiRequest.Options mOptions;

        CreatePaymentMethodTask(@NonNull StripeApiHandler apiHandler,
                                @NonNull PaymentMethodCreateParams paymentMethodCreateParams,
                                @NonNull String publishableKey,
                                @Nullable String stripeAccount,
                                @NonNull ApiResultCallback<PaymentMethod> callback) {
            super(callback);
            mApiHandler = apiHandler;
            mPaymentMethodCreateParams = paymentMethodCreateParams;
            mOptions = ApiRequest.Options.create(publishableKey, stripeAccount);
        }

        @Nullable
        @Override
        PaymentMethod getResult() throws StripeException {
            return mApiHandler.createPaymentMethod(mPaymentMethodCreateParams, mOptions);
        }
    }

    private static class CreateTokenTask extends ApiOperation<Token> {
        @NonNull private final StripeApiHandler mApiHandler;
        @NonNull private final Map<String, Object> mTokenParams;
        @NonNull private final ApiRequest.Options mOptions;
        @NonNull @Token.TokenType private final String mTokenType;

        CreateTokenTask(
                @NonNull final StripeApiHandler apiHandler,
                @NonNull final Map<String, Object> tokenParams,
                @NonNull final ApiRequest.Options options,
                @NonNull @Token.TokenType final String tokenType,
                @NonNull final TokenCallback callback) {
            super(callback);
            mApiHandler = apiHandler;
            mTokenParams = tokenParams;
            mTokenType = tokenType;
            mOptions = options;
        }

        @Nullable
        @Override
        Token getResult() throws StripeException {
            return mApiHandler.createToken(mTokenParams, mOptions, mTokenType);
        }
    }
}
