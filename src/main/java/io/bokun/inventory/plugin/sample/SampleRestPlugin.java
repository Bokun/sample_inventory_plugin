package io.bokun.inventory.plugin.sample;

import java.io.*;
import java.util.*;

import javax.annotation.*;

import com.google.common.collect.*;
import com.google.gson.*;
import com.google.inject.*;
import com.squareup.okhttp.*;
import io.bokun.inventory.plugin.api.rest.*;
import io.undertow.server.*;
import org.slf4j.*;

import static io.bokun.inventory.plugin.api.rest.PluginCapability.*;
import static io.undertow.util.Headers.*;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * The actual Inventory Service API implementation.
 *
 * @author Mindaugas Žakšauskas
 */
public class SampleRestPlugin {

    private static final Logger log = LoggerFactory.getLogger(SampleRestPlugin.class);

    /**
     * Default OkHttp read timeout: how long to wait (in seconds) for the backend to respond to requests.
     */
    private static final long DEFAULT_READ_TIMEOUT = 30L;

    private final OkHttpClient client;

    @Inject
    public SampleRestPlugin() {
        this.client = new OkHttpClient();
        client.setReadTimeout(DEFAULT_READ_TIMEOUT, SECONDS);
    }

    // helper method to express string as required string parameter structure, required by the REST API
    private PluginConfigurationParameter asRequiredStringParameter(String name) {
        PluginConfigurationParameter param = new PluginConfigurationParameter();
        param.setName(name);
        param.setType(PluginParameterDataType.STRING);
        param.setRequired(true);
        return param;
    }

    // helper method to express string as required long parameter structure, required by the REST API
    private PluginConfigurationParameter asRequiredLongParameter(String name) {
        PluginConfigurationParameter param = new PluginConfigurationParameter();
        param.setName(name);
        param.setType(PluginParameterDataType.LONG);
        param.setRequired(true);
        return param;
    }

    /**
     * Responds to <tt>/plugin/definition</tt> by sending back simple plugin definition JSON object.
     */
    public void getDefinition(@Nonnull HttpServerExchange exchange) {
        PluginDefinition definition = new PluginDefinition();
        definition.setName("Sample plugin");
        definition.setDescription("Provides availability and accepts bookings into <YourCompany> booking system");

        definition.getCapabilities().add(AVAILABILITY);
        definition.getCapabilities().add(RESERVATIONS);

        // reuse parameter names from grpc
        definition.getParameters().add(asRequiredStringParameter(Configuration.SAMPLE_API_SCHEME));    // e.g. https
        definition.getParameters().add(asRequiredStringParameter(Configuration.SAMPLE_API_HOST));      // e.g. your-api.your-company.com
        definition.getParameters().add(asRequiredLongParameter(Configuration.SAMPLE_API_PORT));        // e.g. 443
        definition.getParameters().add(asRequiredStringParameter(Configuration.SAMPLE_API_PATH));      // e.g. /api/1
        definition.getParameters().add(asRequiredStringParameter(Configuration.SAMPLE_API_USERNAME));
        definition.getParameters().add(asRequiredStringParameter(Configuration.SAMPLE_API_PASSWORD));

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(definition));
    }

    /**
     * Helper method which creates {@link HttpUrl.Builder} based on configuration (scheme/host/port/path).
     *
     * @param configuration configuration to use to create OkHttp url builder.
     * @return url builder which is ready to use.
     */
    @Nonnull
    private HttpUrl.Builder getUrlBuilder(@Nonnull Configuration configuration) {
        return new HttpUrl.Builder()
                .scheme(configuration.scheme)
                .host(configuration.host)
                .port(configuration.port)
                .encodedPath(configuration.apiPath);
    }

    /**
     * This method should list all your products
     */
    public void searchProducts(@Nonnull HttpServerExchange exchange) {
        SearchProductRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), SearchProductRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        // Let's say we are about to call http(s)://yoururl/product to get a list of products
        HttpUrl.Builder urlBuilder = getUrlBuilder(configuration)
                        .addPathSegment("product");
        // Create HTTP get call using basic authorization, taking username/password from the same configuration
        Request getRequest = new Request.Builder()
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", Credentials.basic(configuration.username, configuration.password))
                .url(urlBuilder.build())
                .build();

        String httpResponseBody;
        try {
            client.newCall(getRequest)
                    .execute()
                    .body().string();
        } catch (IOException e) {
            log.error("Error calling request {}", getRequest, e);
            exchange.setStatusCode(500);
            exchange.getResponseSender().send("Error: " + e.getMessage());
            return;
        }

        // Do something with httpResponseBody, e.g. convert this JSON into POJO and convert that POJO into BasicProductInfo
        // Don't forget to filter them by country and city, based on request parameters.
        BasicProductInfo basicProductInfo = new BasicProductInfo();     // you will likely want to run this in a loop, to return multiple products
        basicProductInfo.setId("123");
        basicProductInfo.setName("Mock product");
        basicProductInfo.setDescription("Mock product description");
        basicProductInfo.setPricingCategories(new ArrayList<>());
        {
            PricingCategory adult = new PricingCategory();
            adult.setId("ADT");         // can be any code as long as it is unique per pricing category. This will also connect with other calls
            adult.setLabel("Adult");
            basicProductInfo.getPricingCategories().add(adult);
        }
        {
            PricingCategory child = new PricingCategory();
            child.setId("CHD");
            child.setLabel("Adult");
            basicProductInfo.getPricingCategories().add(child);
        }

        basicProductInfo.setCities(ImmutableList.of("London"));
        basicProductInfo.setCountries(ImmutableList.of("GB"));

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(Lists.newArrayList(basicProductInfo)));
    }

    /**
     * Return detailed information about one particular product by given ID.
     */
    public void getProductById(HttpServerExchange exchange) {
        GetProductByIdRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), GetProductByIdRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        // similar to searchProducts except this should return a single product with a bit more information
        ProductDescription description = new ProductDescription();
        description.setId("123");
        description.setName("Mock product");
        description.setDescription("Mock product description");

        description.setPricingCategories(new ArrayList<>());
        {
            PricingCategory adult = new PricingCategory();
            adult.setId("ADT");         // can be any code as long as it is unique per pricing category. This will also connect with other calls
            adult.setLabel("Adult");
            description.getPricingCategories().add(adult);
        }
        {
            PricingCategory child = new PricingCategory();
            child.setId("CHD");
            child.setLabel("Adult");
            description.getPricingCategories().add(child);
        }

        Rate rate = new Rate();
        rate.setId("standard");
        rate.setLabel("Standard");
        description.setRates(ImmutableList.of(rate));
        description.setBookingType(BookingType.DATE_AND_TIME);
        description.setProductCategory(ProductCategory.ACTIVITIES);
        description.setTicketSupport(
                ImmutableList.of(
                        TicketSupport.TICKET_PER_BOOKING
                )
        );
        description.setCities(ImmutableList.of("London"));
        description.setCountries(ImmutableList.of("GB"));

        Time startTime = new Time();
        startTime.setHour(8);
        startTime.setMinute(15);
        description.setStartTimes(ImmutableList.of(startTime));

        {                                                                               // opening hours block
            OpeningHoursWeekday openingHoursMonday = new OpeningHoursWeekday();
            openingHoursMonday.setOpen24Hours(false);
            OpeningHoursTimeInterval mondayIntervalAM = new OpeningHoursTimeInterval();
            mondayIntervalAM.setOpenFrom("08:00");
            mondayIntervalAM.setOpenForHours(4);
            mondayIntervalAM.setOpenForMinutes(0);
            OpeningHoursTimeInterval mondayIntervalPM = new OpeningHoursTimeInterval();
            mondayIntervalPM.setOpenFrom("13:00");
            mondayIntervalPM.setOpenForHours(4);
            mondayIntervalPM.setOpenForMinutes(0);
            openingHoursMonday.setTimeIntervals(ImmutableList.of(mondayIntervalAM, mondayIntervalPM));
            OpeningHours openingHours = new OpeningHours();
            openingHours.setMonday(openingHoursMonday);
            description.setAllYearOpeningHours(openingHours);
        }

        {                                                                               // extras block
            Extra extra = new Extra();
            extra.setId("some-extra-id");
            extra.setTitle("Some extra title");
            extra.setDescription("Some extra description");
            extra.setOptional(false);
            extra.setMaxPerBooking(1);
            extra.setLimitByPax(false);
            extra.setIncreasesCapacity(false);
            description.setExtras(ImmutableList.of(extra));
        }

        description.setTicketType(TicketType.QR_CODE);
        description.setMeetingType(MeetingType.MEET_ON_LOCATION);

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(description));
    }

    /**
     * A set of product ids provided, return their availability over given date range ("shallow" call).
     * This will return a subset of product IDs passed on via ProductAvailabilityRequest.
     * Note: even though request contains capacity and date range, for a matching product it is enough to have availabilities for *some* dates over
     * requested period. Subsequent GetProductAvailability request will clarify precise dates and capacities.
     */
    public void getAvailableProducts(HttpServerExchange exchange) {
        ProductsAvailabilityRequest request = new Gson().fromJson(new InputStreamReader(exchange.getInputStream()), ProductsAvailabilityRequest.class);
        Configuration configuration = Configuration.fromRestParameters(request.getParameters());

        if (!request.getExternalProductIds().contains("123")) {
            throw new IllegalStateException("Previous call only returned product having id=123");
        }

        ProductsAvailabilityResponse response = new ProductsAvailabilityResponse();
        response.setActualCheckDone(true);
        response.setProductId("123");

        exchange.getResponseHeaders().put(CONTENT_TYPE, "application/json; charset=utf-8");
        exchange.getResponseSender().send(new Gson().toJson(ImmutableList.of(response)));
    }

//    /**
//     * Get availability of a particular product over a date range. This request should follow GetAvailableProducts and provide more details on
//     * precise dates/times for each product as well as capacity for each date. This call, however, is for a single product only (as opposed to
//     * {@link #getAvailableProducts(ProductsAvailabilityRequest, StreamObserver)}) which checks many products but only does a basic shallow check.
//     */
//    @Override
//    public void getProductAvailability(ProductAvailabilityRequest request, StreamObserver<ProductAvailabilityWithRatesResponse> responseObserver) {
//        log.trace("In ::getProductAvailability");
//        LocalDate tomorrow = LocalDate.now().plusDays(1L);
//        responseObserver.onNext(
//                ProductAvailabilityWithRatesResponse.newBuilder()
//                        .setCapacity(10)
//                        .setDate(
//                                Date.newBuilder()
//                                        .setYear(tomorrow.getYear())
//                                        .setMonth(tomorrow.getMonthValue())
//                                        .setDay(tomorrow.getDayOfMonth())
//                        )
//                        .setTime(
//                                Time.newBuilder()
//                                        .setHour(8)
//                                        .setMinute(15)
//                        )
//                        .addRates(
//                                RateWithPrice.newBuilder()
//                                        .setRateId("standard")
//                                        .setPricePerPerson(
//                                                PricePerPerson.newBuilder()
//                                                        .addPricingCategoryWithPrice(
//                                                                PricingCategoryWithPrice.newBuilder()
//                                                                        .setPricingCategoryId("ADT")
//                                                                        .setPrice(
//                                                                                Price.newBuilder()
//                                                                                        .setAmount("100")
//                                                                                        .setCurrency("EUR")
//                                                                        )
//                                                        )
//                                                        .addPricingCategoryWithPrice(
//                                                                PricingCategoryWithPrice.newBuilder()
//                                                                        .setPricingCategoryId("CHD")
//                                                                        .setPrice(
//                                                                                Price.newBuilder()
//                                                                                        .setAmount("10")
//                                                                                        .setCurrency("EUR")
//                                                                        )
//                                                        )
//                                        )
//                        )
//                        .build()
//        );
//        responseObserver.onCompleted();
//        log.trace("Out ::getProductAvailability");
//    }
//
//    /**
//     * This call secures necessary resource(s), such as activity time slot which can later become a booking. The reservation should be held for some
//     * limited time, and reverted back to being available if the booking is not confirmed.
//     *
//     * Only implement this method if {@link PluginCapability#SUPPORTS_RESERVATIONS} is among capabilities of your {@link PluginDefinition}.
//     * Otherwise you are only required to implement {@link #createAndConfirmBooking(CreateConfirmBookingRequest, StreamObserver)} which does both
//     * reservation and confirmation, this method can be left empty or non-overridden.
//     */
//    @Override
//    public void createReservation(ReservationRequest request, StreamObserver<ReservationResponse> responseObserver) {
//        log.trace("In ::createReservation");
//        responseObserver.onNext(
//                ReservationResponse.newBuilder()
//                        .setSuccessfulReservation(
//                                SuccessfulReservation.newBuilder()
//                                        .setReservationConfirmationCode(UUID.randomUUID().toString())
//                        )
//                        .build()
//        );
//        responseObserver.onCompleted();
//        log.trace("Out ::createReservation");
//    }
//
//    /**
//     * Once reserved, proceed with booking. This will be called in case if reservation has succeeded.
//     *
//     * Only implement this method if {@link PluginCapability#SUPPORTS_RESERVATIONS} is among capabilities of your {@link PluginDefinition}.
//     * Otherwise you are only required to implement {@link #createAndConfirmBooking(CreateConfirmBookingRequest, StreamObserver)} which does both
//     * reservation and confirmation, this method can be left empty or non-overridden.
//     */
//    @Override
//    public void confirmBooking(ConfirmBookingRequest request, StreamObserver<ConfirmBookingResponse> responseObserver) {
//        log.trace("In ::confirmBooking");
//        processBookingSourceInfo(request.getReservationData().getBookingSource());
//        String confirmationCode = UUID.randomUUID().toString();
//        responseObserver.onNext(
//                ConfirmBookingResponse.newBuilder()
//                        .setSuccessfulBooking(
//                                SuccessfulBooking.newBuilder()
//                                        .setBookingConfirmationCode(confirmationCode)
//                                        .setBookingTicket(
//                                                Ticket.newBuilder()
//                                                        .setQrTicket(
//                                                                QrTicket.newBuilder()
//                                                                        .setTicketBarcode(confirmationCode + "_ticket")
//                                                        )
//                                        )
//                        )
//                        .build()
//        );
//        responseObserver.onCompleted();
//        log.trace("Out ::confirmBooking");
//    }
//
//    /**
//     * Example code to get info about the booking initiator.
//     * Here you can see which data is available in each bookingSource.getSegment() case
//     * @param bookingSource bookinSource data structure that is provided in booking requests
//     */
//    void processBookingSourceInfo(BookingSource bookingSource) {
//        log.trace("Sales segment: {}",
//                bookingSource.getSegment().name());
//        log.trace("Booking channel: {} '{}'",
//                bookingSource.getBookingChannel().getId(),
//                bookingSource.getBookingChannel().getTitle());
//        switch (bookingSource.getSegment()) {
//            case OTA:
//                log.trace("OTA system: {}",
//                        bookingSource.getBookingChannel().getSystemType());
//                break;
//            case MARKETPLACE:
//                log.trace("Reseller vendor: {} '{}' reg.no. {}",
//                        bookingSource.getMarketplaceVendor().getId(),
//                        bookingSource.getMarketplaceVendor().getTitle(),
//                        bookingSource.getMarketplaceVendor().getCompanyRegistrationNumber());
//                break;
//            case AGENT_AREA:
//                log.trace("Booking agent: {} '{}' reg.no. {}",
//                        bookingSource.getBookingAgent().getId(),
//                        bookingSource.getBookingAgent().getTitle(),
//                        bookingSource.getBookingAgent().getCompanyRegistrationNumber());
//                break;
//            case DIRECT_OFFLINE:
//                log.trace("Extranet user: {} '{}'",
//                        bookingSource.getExtranetUser().getEmail(),
//                        bookingSource.getExtranetUser().getFullName());
//                break;
//        }
//    }
//
//    /**
//     * Only implement this method if {@link PluginCapability#SUPPORTS_RESERVATIONS} is <b>NOT</b> among capabilities of your {@link PluginDefinition}.
//     * Otherwise you are only required to implement both {@link #createReservation(ReservationRequest, StreamObserver)} and {@link
//     * #confirmBooking(ConfirmBookingRequest, StreamObserver)} separately; this method should remain empty or non-overridden.
//     */
//    @Override
//    public void createAndConfirmBooking(CreateConfirmBookingRequest request, StreamObserver<ConfirmBookingResponse> responseObserver) {
//        log.trace("In ::createAndConfirmBooking");          // should never happen
//        throw new UnsupportedOperationException();
//    }
//
//    /**
//     * Once booked, a booking may be cancelled using booking ref number.
//     * If your system does not support booking cancellation, one of the current workarounds is to create a cancellation policy (on the Bokun end)
//     * which offers no refund. Then a cancellation does not have any monetary effect.
//     */
//    @Override
//    public void cancelBooking(CancelBookingRequest request, StreamObserver<CancelBookingResponse> responseObserver) {
//        log.trace("In ::cancelBooking");
//        responseObserver.onNext(
//                CancelBookingResponse.newBuilder()
//                        .setSuccessfulCancellation(
//                                SuccessfulCancellation.newBuilder()
//                        )
//                        .build()
//        );
//        responseObserver.onCompleted();
//        log.trace("Out ::cancelBooking");
//    }
//
//    /**
//     * Holder of configuration parameter values.
//     */
//    public static final class Configuration {
//
//        static final String SAMPLE_API_SCHEME = "SAMPLE_API_SCHEME";
//        static final String SAMPLE_API_HOST = "SAMPLE_API_HOST";
//        static final String SAMPLE_API_PORT = "SAMPLE_API_PORT";
//        static final String SAMPLE_API_PATH = "SAMPLE_API_PATH";
//        static final String SAMPLE_API_USERNAME = "SAMPLE_API_USERNAME";
//        static final String SAMPLE_API_PASSWORD = "SAMPLE_API_PASSWORD";
//
//        String scheme;
//        String host;
//        int port;
//        String apiPath;
//        String username;
//        String password;
//
//        Configuration(Iterable<PluginConfigurationParameterValue> configParameters) {
//            for (PluginConfigurationParameterValue parameterValue : configParameters) {
//                switch (parameterValue.getName()) {
//                    case SAMPLE_API_SCHEME: scheme = parameterValue.getValue(); break;
//                    case SAMPLE_API_HOST: host = parameterValue.getValue(); break;
//                    case SAMPLE_API_PORT: port = Integer.parseInt(parameterValue.getValue()); break;
//                    case SAMPLE_API_PATH: apiPath = parameterValue.getValue(); break;
//                    case SAMPLE_API_USERNAME: username = parameterValue.getValue(); break;
//                    case SAMPLE_API_PASSWORD: password = parameterValue.getValue(); break;
//                }
//            }
//        }
//    }
}
