package com.stripe.samplestore;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.Size;
import android.support.v4.app.FragmentManager;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.gms.identity.intents.model.UserAddress;
import com.google.android.gms.wallet.Cart;
import com.google.android.gms.wallet.LineItem;
import com.google.android.gms.wallet.MaskedWallet;
import com.jakewharton.rxbinding.view.RxView;
import com.stripe.android.CustomerSession;
import com.stripe.android.PaymentConfiguration;
import com.stripe.android.PaymentSession;
import com.stripe.android.PaymentSessionConfig;
import com.stripe.android.PaymentSessionData;
import com.stripe.android.Stripe;
import com.stripe.android.model.Address;
import com.stripe.android.model.Customer;
import com.stripe.android.model.CustomerSource;
import com.stripe.android.model.ShippingInformation;
import com.stripe.android.model.ShippingMethod;
import com.stripe.android.model.Source;
import com.stripe.android.model.SourceCardData;
import com.stripe.samplestore.service.StripeService;
import com.stripe.wrap.pay.AndroidPayConfiguration;
import com.stripe.wrap.pay.utils.CartManager;
import com.stripe.wrap.pay.utils.PaymentUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;

import retrofit2.Retrofit;
import rx.Observable;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

import static com.stripe.android.view.PaymentFlowActivity.EVENT_SHIPPING_INFO_PROCESSED;
import static com.stripe.android.view.PaymentFlowActivity.EVENT_SHIPPING_INFO_SUBMITTED;
import static com.stripe.android.view.PaymentFlowActivity.EXTRA_DEFAULT_SHIPPING_METHOD;
import static com.stripe.android.view.PaymentFlowActivity.EXTRA_IS_SHIPPING_INFO_VALID;
import static com.stripe.android.view.PaymentFlowActivity.EXTRA_SHIPPING_INFO_DATA;
import static com.stripe.android.view.PaymentFlowActivity.EXTRA_VALID_SHIPPING_METHODS;
import static com.stripe.wrap.pay.activity.StripeAndroidPayActivity.EXTRA_CART;

public class PaymentActivity extends AppCompatActivity {

    private static final String TOTAL_LABEL = "Total:";
    private static final Locale LOC = Locale.US;

    /*
     * Change this to your publishable key.
     *
     * You can get your key here: https://dashboard.stripe.com/account/apikeys
     */
    private static final String PUBLISHABLE_KEY =
            "pk_test_GM1935gITkCi5UwpnUFIhXP8";

    private BroadcastReceiver mBroadcastReceiver;
    private CartManager mCartManager;
    private CompositeSubscription mCompositeSubscription;
    private ProgressDialogFragment mProgressDialogFragment;
    private Stripe mStripe;

    private LinearLayout mCartItemLayout;

    private String mCurrentShippingKey;
    private Button mConfirmPaymentButton;
    private TextView mEnterShippingInfo;
    private TextView mEnterPaymentInfo;

    private PaymentSession mPaymentSession;

    public static Intent createIntent(@NonNull Context context, @NonNull Cart cart) {
        Intent intent = new Intent(context, PaymentActivity.class);
        intent.putExtra(EXTRA_CART, cart);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment);
        PaymentConfiguration.init(PUBLISHABLE_KEY);

        Bundle extras = getIntent().getExtras();
        Cart cart = extras.getParcelable(EXTRA_CART);
        mCartManager = new CartManager(cart);

        mCartItemLayout = findViewById(R.id.cart_list_items);

        addCartItems();
        mCompositeSubscription = new CompositeSubscription();

        mProgressDialogFragment =
                ProgressDialogFragment.newInstance(R.string.completing_purchase);

        mConfirmPaymentButton = findViewById(R.id.btn_purchase);
       // mConfirmPaymentButton.setEnabled(false);
        updateConfirmPaymentButton();
        mEnterShippingInfo = findViewById(R.id.shipping_info);
        mEnterPaymentInfo = findViewById(R.id.payment_source);
        RxView.clicks(mEnterShippingInfo)
                .subscribe(new Action1<Void>() {
                    @Override
                    public void call(Void aVoid) {
                        mPaymentSession.presentShippingFlow();
                    }
                });
        RxView.clicks(mEnterPaymentInfo)
                .subscribe(new Action1<Void>() {
                    @Override
                    public void call(Void aVoid) {
                        mPaymentSession.presentPaymentMethodSelection();
                    }
                });
        RxView.clicks(mConfirmPaymentButton)
                .subscribe(new Action1<Void>() {
                    @Override
                    public void call(Void aVoid) {
                        attemptPurchase();
                    }
                });

        mStripe = new Stripe(this);
        setupPaymentSession();
        mBroadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                ShippingInformation shippingInformation = intent.getParcelableExtra(EXTRA_SHIPPING_INFO_DATA);
                Intent shippingInfoProcessedIntent = new Intent(EVENT_SHIPPING_INFO_PROCESSED);
                if (shippingInformation.getAddress() == null || !shippingInformation.getAddress().getCountry().equals(Locale.US.getCountry())) {
                    shippingInfoProcessedIntent.putExtra(EXTRA_IS_SHIPPING_INFO_VALID, false);
                } else {
                    ArrayList<ShippingMethod> shippingMethods = getValidShippingMethods(shippingInformation);
                    shippingInfoProcessedIntent.putExtra(EXTRA_IS_SHIPPING_INFO_VALID, true);
                    shippingInfoProcessedIntent.putParcelableArrayListExtra(EXTRA_VALID_SHIPPING_METHODS, shippingMethods);
                    shippingInfoProcessedIntent.putExtra(EXTRA_DEFAULT_SHIPPING_METHOD, shippingMethods.get(0));
                }
                LocalBroadcastManager.getInstance(PaymentActivity.this).sendBroadcast(shippingInfoProcessedIntent);
            }
        };
        LocalBroadcastManager.getInstance(this).registerReceiver(mBroadcastReceiver,
                new IntentFilter(EVENT_SHIPPING_INFO_SUBMITTED));
    }

//    /**
//     * This is where the chargeable Stripe object is returned. You can send the ID of the
//     * {@link StripePaymentSource} to your server to make a charge.
//     *
//     * @param wallet the {@link FullWallet} returned from Google.
//     * @param paymentSource the {@link StripePaymentSource} with chargeable ID
//     */
//    @Override
//    protected void onStripePaymentSourceReturned(FullWallet wallet,
//                                                 StripePaymentSource paymentSource) {
//        super.onStripePaymentSourceReturned(wallet, paymentSource);
//        completePurchase(paymentSource.getId());
//    }

    /*
     * Cleaning up all Rx subscriptions in onDestroy.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mCompositeSubscription != null) {
            mCompositeSubscription.unsubscribe();
            mCompositeSubscription = null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        mPaymentSession.handlePaymentData(requestCode, resultCode, data);
    }

    private void updateShippingAndTax(@NonNull MaskedWallet maskedWallet) {
        UserAddress shippingAddress = maskedWallet.getBuyerShippingAddress();
        UserAddress billingAddress = maskedWallet.getBuyerBillingAddress();
        if (mCurrentShippingKey != null) {
            mCartManager.removeLineItem(mCurrentShippingKey);
            mCurrentShippingKey = null;
        }

        mCurrentShippingKey = mCartManager.addShippingLineItem("Shipping", determineShippingCost(shippingAddress));
        mCartManager.setTaxLineItem("Tax", determineTax(billingAddress));
    }

    private void updateCartTotals() {
        addCartItems();
    }

    private void updateConfirmPaymentButton() {
        Long price = mCartManager.getTotalPrice();

        if (price != null) {
            mConfirmPaymentButton.setText(String.format(Locale.ENGLISH,
                    "Pay %s", StoreUtils.getPriceString(price, null)));
        }
    }

    private void addCartItems() {
        mCartItemLayout.removeAllViewsInLayout();
        String currencySymbol = AndroidPayConfiguration.getInstance()
                .getCurrency().getSymbol(Locale.US);

        Collection<LineItem> items = mCartManager.getLineItemsRegular().values();
        addLineItems(currencySymbol, items.toArray(new LineItem[items.size()]));

        items = mCartManager.getLineItemsShipping().values();
        addLineItems(currencySymbol, items.toArray(new LineItem[items.size()]));

        if (mCartManager.getLineItemTax() != null) {
            addLineItems(currencySymbol, mCartManager.getLineItemTax());
        }

        View totalView = LayoutInflater.from(this).inflate(
                R.layout.cart_item, mCartItemLayout, false);
        boolean shouldDisplayTotal = fillOutTotalView(totalView, currencySymbol);
        if (shouldDisplayTotal) {
            mCartItemLayout.addView(totalView);
        }
    }

    private void addLineItems(String currencySymbol, LineItem... items) {
        for (LineItem item : items) {
            View view = LayoutInflater.from(this).inflate(
                    R.layout.cart_item, mCartItemLayout, false);
            fillOutCartItemView(item, view, currencySymbol);
            mCartItemLayout.addView(view);
        }
    }

    /**
     * This is just a toy way to determine a fake shipping cost. You would apply
     * your genuine costs here.
     *
     * @param address the {@link UserAddress} object returned from Android Pay
     * @return a shipping cost in the currency used, in its lowest denomination
     */
    private long determineShippingCost(UserAddress address) {
        if (address == null) {
            return 200L;
        }
        return address.getAddress1().length() * 7L;
    }

    /**
     * Again, this is a toy way to determine a fake tax amount. You may need to determine
     * taxes based on the shipping address, billing address, cost of the
     * {@link LineItem.Role#REGULAR} items in your cart, or some combination of the three.
     *
     * @param address the {@link UserAddress} object returned from Android Pay
     * @return a tax amount in the currency used, in its lowest denomination
     */
    private long determineTax(UserAddress address) {
        if (address == null) {
            return 200L;
        }
        return address.getAddress1().length() * 3L;
    }

    private boolean fillOutTotalView(View view, String currencySymbol) {
        TextView[] itemViews = getItemViews(view);
        Long totalPrice = mCartManager.getTotalPrice();
        if (totalPrice != null) {
            itemViews[0].setText(TOTAL_LABEL);
            String priceString = PaymentUtils.getPriceString(totalPrice,
                    AndroidPayConfiguration.getInstance().getCurrency());
            priceString = currencySymbol + priceString;
            itemViews[3].setText(priceString);
            return true;
        }
        return false;
    }

    private void fillOutCartItemView(LineItem item, View view, String currencySymbol) {
        TextView[] itemViews = getItemViews(view);

        itemViews[0].setText(item.getDescription());
        if (!TextUtils.isEmpty(item.getQuantity())) {
            String quantityPriceString = "X " + item.getQuantity() + " @";
            itemViews[1].setText(quantityPriceString);
        }

        if (!TextUtils.isEmpty(item.getUnitPrice())) {
            String unitPriceString = currencySymbol + item.getUnitPrice();
            itemViews[2].setText(unitPriceString);
        }

        if (!TextUtils.isEmpty(item.getTotalPrice())) {
            String totalPriceString = currencySymbol + item.getTotalPrice();
            itemViews[3].setText(totalPriceString);
        }
    }

    @Size(value = 4)
    private TextView[] getItemViews(View view) {
        TextView labelView = view.findViewById(R.id.tv_cart_emoji);
        TextView quantityView = view.findViewById(R.id.tv_cart_quantity);
        TextView unitPriceView = view.findViewById(R.id.tv_cart_unit_price);
        TextView totalPriceView = view.findViewById(R.id.tv_cart_total_price);
        TextView[] itemViews = { labelView, quantityView, unitPriceView, totalPriceView };
        return itemViews;
    }

    private void attemptPurchase() {
        CustomerSession.getInstance().retrieveCurrentCustomer(new CustomerSession.CustomerRetrievalListener() {
            @Override
            public void onCustomerRetrieved(@NonNull Customer customer) {
                String sourceId = customer.getDefaultSource();
                if (sourceId == null) {
                    displayError("No payment method selected");
                    return;
                }
                CustomerSource source = customer.getSourceById(sourceId);
                proceedWithPurchaseIf3DSCheckIsNotNecessary(source.asSource());
            }

            @Override
            public void onError(int errorCode, @Nullable String errorMessage) {
                displayError("Error getting payment method");
            }
        });

    }

    private void proceedWithPurchaseIf3DSCheckIsNotNecessary(Source source) {
        if (source == null || !Source.CARD.equals(source.getType())) {
            displayError("Something went wrong - this should be rare");
            return;
        }

        SourceCardData cardData = (SourceCardData) source.getSourceTypeModel();
        if (SourceCardData.REQUIRED.equals(cardData.getThreeDSecureStatus())) {
            // In this case, you would need to ask the user to verify the purchase.
            // You can see an example of how to do this in the 3DS example application.
            // In stripe-android/example.
        } else {
            // If 3DS is not required, you can charge the source.
            completePurchase(source.getId());
        }
    }

    private void completePurchase(String sourceId) {
        Retrofit retrofit = RetrofitFactory.getInstance();
        StripeService stripeService = retrofit.create(StripeService.class);
        Long price = mCartManager.getTotalPrice();

        if (price == null) {
            // This should be rare, and only occur if there is somehow a mix of currencies in
            // the CartManager (only possible if those are put in as LineItem objects manually).
            // If this is the case, you can put in a cart total price manually by calling
            // CartManager.setTotalPrice.
            return;
        }

        Observable<Void> stripeResponse = stripeService.createQueryCharge(price, sourceId);
        final FragmentManager fragmentManager = getSupportFragmentManager();
        mCompositeSubscription.add(stripeResponse
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnSubscribe(
                        new Action0() {
                            @Override
                            public void call() {
                                if (mProgressDialogFragment != null &&
                                        !mProgressDialogFragment.isAdded())
                                    mProgressDialogFragment.show(fragmentManager, "progress");
                            }
                        })
                .doOnUnsubscribe(
                        new Action0() {
                            @Override
                            public void call() {
                                if (mProgressDialogFragment != null
                                        && mProgressDialogFragment.isVisible()) {
                                    mProgressDialogFragment.dismiss();
                                }
                            }
                        })
                .subscribe(
                        new Action1<Void>() {
                            @Override
                            public void call(Void aVoid) {
                                finishCharge();
                            }
                        },
                        new Action1<Throwable>() {
                            @Override
                            public void call(Throwable throwable) {
                                displayError(throwable.getLocalizedMessage());
                            }
                        }));
    }

    private void displayError(String errorMessage) {
        AlertDialog alertDialog = new AlertDialog.Builder(this).create();
        alertDialog.setTitle("Error");
        alertDialog.setMessage(errorMessage);
        alertDialog.setButton(AlertDialog.BUTTON_NEUTRAL, "OK",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
        alertDialog.show();
    }

    private void finishCharge() {
        Long price = mCartManager.getTotalPrice();

        if (price == null) {
            return;
        }

        Intent data = StoreActivity.createPurchaseCompleteIntent(price);
        setResult(RESULT_OK, data);
        finish();
    }

    private void dismissKeyboard() {
        InputMethodManager inputManager =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.toggleSoftInput(0, 0);
    }

    private void setupPaymentSession() {
        mPaymentSession = new PaymentSession(this);
        mPaymentSession.init(new PaymentSession.PaymentSessionListener() {
            @Override
            public void onCommunicatingStateChanged(boolean isCommunicating) {
                if (isCommunicating) {
                    mProgressDialogFragment.show(getSupportFragmentManager(), "progress");
                } else {
                    mProgressDialogFragment.dismiss();
                }
            }

            @Override
            public void onError(int errorCode, @Nullable String errorMessage) {
                displayError(errorMessage);
            }

            @Override
            public void onPaymentSessionDataChanged(@NonNull PaymentSessionData data) {
                if (data.getShippingMethod() != null) {
                    mEnterShippingInfo.setText(data.getShippingMethod().getLabel());
                }

                if (data.getSelectedPaymentMethodId() != null) {
                    CustomerSession.getInstance().retrieveCurrentCustomer(new CustomerSession.CustomerRetrievalListener() {
                        @Override
                        public void onCustomerRetrieved(@NonNull Customer customer) {
                            String sourceId = customer.getDefaultSource();
                            if (sourceId == null) {
                                displayError("No payment method selected");
                                return;
                            }
                            CustomerSource source = customer.getSourceById(sourceId);
                            mEnterPaymentInfo.setText(source.getSourceType());
                        }

                        @Override
                        public void onError(int errorCode, @Nullable String errorMessage) {

                        }
                    });
                }

                if (data.isPaymentReadyToCharge()) {
                    mConfirmPaymentButton.setEnabled(true);
                }

            }
        }, new PaymentSessionConfig.Builder()
                .setPrepopulatedShippingInfo(getExampleShippingInfo())
                .build());

    }


    private ArrayList<ShippingMethod> getValidShippingMethods(ShippingInformation shippingInformation) {
        ArrayList<ShippingMethod> shippingMethods = new ArrayList<>();
        shippingMethods.add(new ShippingMethod("UPS Ground", "ups-ground", "Arrives in 3-5 days", 0, "USD"));
        shippingMethods.add(new ShippingMethod("FedEx", "fedex", "Arrives tomorrow", 599, "USD"));
        if (shippingInformation.getAddress() != null && shippingInformation.getAddress().getPostalCode().equals("94110")) {
            shippingMethods.add(new ShippingMethod("1 Hour Courier", "courier", "Arrives in the next hour", 1099, "USD"));
        }
        return shippingMethods;
    }

    private ShippingInformation getExampleShippingInfo() {
        Address address = new Address.Builder()
                .setCity("San Francisco")
                .setCountry("US")
                .setLine1("123 Market St")
                .setLine2("#345")
                .setPostalCode("94110")
                .setState("CA")
                .build();
        return new ShippingInformation(address, "Fake Name", "6504604645");
    }

}
