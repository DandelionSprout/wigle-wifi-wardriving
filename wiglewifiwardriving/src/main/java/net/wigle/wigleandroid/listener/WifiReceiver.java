package net.wigle.wigleandroid.listener;

import static android.location.LocationManager.GPS_PROVIDER;

import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

import net.wigle.wigleandroid.model.ConcurrentLinkedHashMap;
import net.wigle.wigleandroid.DashboardFragment;
import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.ListFragment;
import net.wigle.wigleandroid.MainActivity;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.NetworkListAdapter;
import net.wigle.wigleandroid.model.NetworkType;
import net.wigle.wigleandroid.FilterMatcher;
import net.wigle.wigleandroid.R;
import net.wigle.wigleandroid.util.WiGLEToast;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.location.Location;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.telephony.CellIdentityCdma;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoCdma;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellLocation;
import android.telephony.CellSignalStrengthCdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.NeighboringCellInfo;
import android.telephony.TelephonyManager;
import android.telephony.cdma.CdmaCellLocation;
import android.telephony.gsm.GsmCellLocation;

import com.google.android.gms.maps.model.LatLng;

public class WifiReceiver extends BroadcastReceiver {
    private MainActivity mainActivity;
    private final DatabaseHelper dbHelper;
    private NetworkListAdapter listAdapter;
    private final SimpleDateFormat timeFormat;
    private final NumberFormat numberFormat1;
    private final SsidSpeaker ssidSpeaker;

    private Handler wifiTimer;
    private Location prevGpsLocation;
    private long scanRequestTime = Long.MIN_VALUE;
    private long lastScanResponseTime = Long.MIN_VALUE;
    private long lastWifiUnjamTime = 0;
    private long lastSaveLocationTime = 0;
    private long lastHaveLocationTime = 0;
    private int pendingWifiCount = 0;
    private int pendingCellCount = 0;
    private final long constructionTime = System.currentTimeMillis();
    private long previousTalkTime = System.currentTimeMillis();
    private final Set<String> runNetworks = new HashSet<>();
    private long prevNewNetCount;
    private long prevScanPeriod;
    private boolean scanInFlight = false;

    public static final int SIGNAL_COMPARE = 10;
    public static final int CHANNEL_COMPARE = 11;
    public static final int CRYPTO_COMPARE = 12;
    public static final int FIND_TIME_COMPARE = 13;
    public static final int SSID_COMPARE = 14;

    public static final int CELL_MIN_STRENGTH = -113;

    private static final Map<Integer, String> NETWORK_TYPE_LEGEND;
    static {
        Map<Integer, String> initMap = new HashMap<>();
        initMap.put(TelephonyManager.NETWORK_TYPE_1xRTT, "CDMA - 1xRTT");
        initMap.put(TelephonyManager.NETWORK_TYPE_CDMA, "CDMA"); //CDMA: Either IS95A or IS95B
        initMap.put(TelephonyManager.NETWORK_TYPE_EDGE, "EDGE");
        initMap.put(TelephonyManager.NETWORK_TYPE_EHRPD, "eHRPD");
        initMap.put(TelephonyManager.NETWORK_TYPE_EVDO_0, "CDMA - EvDo rev. 0");
        initMap.put(TelephonyManager.NETWORK_TYPE_EVDO_A, "CDMA - EvDo rev. A");
        initMap.put(TelephonyManager.NETWORK_TYPE_EVDO_B, "CDMA - EvDo rev. B");
        initMap.put(TelephonyManager.NETWORK_TYPE_GPRS, "GPRS");
        initMap.put(TelephonyManager.NETWORK_TYPE_GSM, "GSM");
        initMap.put(TelephonyManager.NETWORK_TYPE_HSDPA, "HSDPA");
        initMap.put(TelephonyManager.NETWORK_TYPE_HSPA, "HSPA");
        initMap.put(TelephonyManager.NETWORK_TYPE_HSPAP, "HSPA+");
        initMap.put(TelephonyManager.NETWORK_TYPE_HSUPA, "HSUPA");
        initMap.put(TelephonyManager.NETWORK_TYPE_IDEN, "iDEN");
        initMap.put(TelephonyManager.NETWORK_TYPE_IWLAN, "IWLAN");
        initMap.put(TelephonyManager.NETWORK_TYPE_LTE, "LTE");
        initMap.put(TelephonyManager.NETWORK_TYPE_TD_SCDMA, "TD_SCDMA");
        initMap.put(TelephonyManager.NETWORK_TYPE_UMTS, "UMTS");
        initMap.put(TelephonyManager.NETWORK_TYPE_UNKNOWN, "UNKNOWN");

        NETWORK_TYPE_LEGEND = Collections.unmodifiableMap(initMap);
    }

    private final Map<String, Map<String,String>> OPERATOR_CACHE;

    //TODO: move these to their own thing?
    public static final Comparator<Network> signalCompare = new Comparator<Network>() {
        @Override
        public int compare( Network a, Network b ) {
            return b.getLevel() - a.getLevel();
        }
    };

    public static final Comparator<Network> channelCompare = new Comparator<Network>() {
        @Override
        public int compare( Network a, Network b ) {
            return a.getFrequency() - b.getFrequency();
        }
    };

    public static final Comparator<Network> cryptoCompare = new Comparator<Network>() {
        @Override
        public int compare( Network a, Network b ) {
            return b.getCrypto() - a.getCrypto();
        }
    };

    public static final Comparator<Network> findTimeCompare = new Comparator<Network>() {
        @Override
        public int compare( Network a, Network b ) {
            return (int) (b.getConstructionTime() - a.getConstructionTime());
        }
    };

    public static final Comparator<Network> ssidCompare = new Comparator<Network>() {
        @Override
        public int compare( Network a, Network b ) {
            return a.getSsid().compareTo( b.getSsid() );
        }
    };

    public WifiReceiver( final MainActivity mainActivity, final DatabaseHelper dbHelper, final Context context ) {
        this.mainActivity = mainActivity;
        this.dbHelper = dbHelper;
        prevScanPeriod = mainActivity.getLocationSetPeriod();
        ListFragment.lameStatic.runNetworks = runNetworks;
        ssidSpeaker = new SsidSpeaker( mainActivity );
        // formats for speech
        timeFormat = new SimpleDateFormat( "h mm aa", Locale.US );
        numberFormat1 = NumberFormat.getNumberInstance( Locale.US );
        if ( numberFormat1 instanceof DecimalFormat ) {
            numberFormat1.setMaximumFractionDigits(1);
        }
        OPERATOR_CACHE = new HashMap<>();
    }

    public void setMainActivity( final MainActivity mainActivity ) {
        this.mainActivity = mainActivity;
        this.ssidSpeaker.setListActivity( mainActivity );
        if (mainActivity != null) {
            prevScanPeriod = mainActivity.getLocationSetPeriod();
            MainActivity.info("WifiReceiver setting prevScanPeriod: " + prevScanPeriod);
        }
    }

    public void setListAdapter( final NetworkListAdapter listAdapter ) {
        this.listAdapter = listAdapter;
    }

    public int getRunNetworkCount() {
        return runNetworks.size();
    }

    public void updateLastScanResponseTime() {
        lastHaveLocationTime = System.currentTimeMillis();
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    public void onReceive( final Context context, final Intent intent ) {
        scanInFlight = false;
        final long now = System.currentTimeMillis();
        lastScanResponseTime = now;
        // final long start = now;
        final WifiManager wifiManager = (WifiManager) mainActivity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        List<ScanResult> results = null;
        try {
            results = wifiManager.getScanResults(); // return can be null!
        }
        catch (final SecurityException ex) {
            MainActivity.info("security exception getting scan results: " + ex, ex);
        }
        catch (final Exception ex) {
            // ignore, happens on some vm's
            MainActivity.info("exception getting scan results: " + ex, ex);
        }

        long nonstopScanRequestTime = Long.MIN_VALUE;
        final SharedPreferences prefs = mainActivity.getSharedPreferences( ListFragment.SHARED_PREFS, 0 );
        final long period = getScanPeriod();
        if ( period == 0 ) {
            // treat as "continuous", so request scan in here
            doWifiScan();
            nonstopScanRequestTime = now;
        }

        final long setPeriod = mainActivity.getLocationSetPeriod();
        if ( setPeriod != prevScanPeriod && mainActivity.isScanning() ) {
            // update our location scanning speed
            MainActivity.info("setting location updates to: " + setPeriod);
            mainActivity.setLocationUpdates(setPeriod, 0f);

            prevScanPeriod = setPeriod;
        }

        // have the gps listener to a self-check, in case it isn't getting updates anymore
        final GPSListener gpsListener = mainActivity.getGPSListener();
        Location location = null;
        if (gpsListener != null) {
            final long gpsTimeout = prefs.getLong(ListFragment.PREF_GPS_TIMEOUT, GPSListener.GPS_TIMEOUT_DEFAULT);
            final long netLocTimeout = prefs.getLong(ListFragment.PREF_NET_LOC_TIMEOUT, GPSListener.NET_LOC_TIMEOUT_DEFAULT);
            gpsListener.checkLocationOK(gpsTimeout, netLocTimeout);
            location = gpsListener.getLocation();
        }

        // save the location every minute, for later runs, or viewing map during loss of location.
        if (now - lastSaveLocationTime > 60000L && location != null) {
            mainActivity.getGPSListener().saveLocation();
            lastSaveLocationTime = now;
        }

        if (location != null) {
            lastHaveLocationTime = now;
        }
        // MainActivity.info("now minus haveloctime: " + (now-lastHaveLocationTime)
        //    + " lastHaveLocationTime: " + lastHaveLocationTime);
        if (now - lastHaveLocationTime > 30000L) {
            // no location in a while, make sure we're subscribed to updates
            MainActivity.info("no location for a while, setting location update period: " + setPeriod);
            mainActivity.setLocationUpdates(setPeriod, 0f);
            // don't do this until another period has passed
            lastHaveLocationTime = now;
        }

        final boolean showCurrent = prefs.getBoolean( ListFragment.PREF_SHOW_CURRENT, true );
        if ( showCurrent && listAdapter != null ) {
            listAdapter.clearWifiAndCell();
        }

        final int preQueueSize = dbHelper.getQueueSize();
        final boolean fastMode = dbHelper.isFastMode();
        final ConcurrentLinkedHashMap<String,Network> networkCache = MainActivity.getNetworkCache();
        boolean somethingAdded = false;
        int resultSize = 0;
        int newWifiForRun = 0;

        final boolean ssidSpeak = prefs.getBoolean( ListFragment.PREF_SPEAK_SSID, false )
                && ! mainActivity.isMuted();

        //TODO: should we memoize the ssidMatcher in the MainActivity state as well?
        final Matcher ssidMatcher = FilterMatcher.getSsidFilterMatcher( prefs, ListFragment.FILTER_PREF_PREFIX );
        final Matcher bssidMatcher = mainActivity.getBssidFilterMatcher( ListFragment.PREF_EXCLUDE_DISPLAY_ADDRS );
        final Matcher bssidDbMatcher = mainActivity.getBssidFilterMatcher( ListFragment.PREF_EXCLUDE_LOG_ADDRS );

        // can be null on shutdown
        if ( results != null ) {
            resultSize = results.size();
            for ( ScanResult result : results ) {
                Network network = networkCache.get( result.BSSID );
                if ( network == null ) {
                    network = new Network( result );
                    networkCache.put( network.getBssid(), network );
                }
                else {
                    // cache hit, just set the level
                    network.setLevel( result.level );
                }

                final boolean added = runNetworks.add( result.BSSID );
                if ( added ) {
                    newWifiForRun++;
                    if ( ssidSpeak ) {
                        ssidSpeaker.add( network.getSsid() );
                    }
                }
                somethingAdded |= added;

                if ( location != null && (added || network.getLatLng() == null) ) {
                    // set the LatLng for mapping
                    final LatLng LatLng = new LatLng( location.getLatitude(), location.getLongitude() );
                    network.setLatLng( LatLng );
                    MainActivity.addNetworkToMap(network);
                }

                // if we're showing current, or this was just added, put on the list
                if ( showCurrent || added ) {
                    if ( FilterMatcher.isOk( ssidMatcher, bssidMatcher, prefs, ListFragment.FILTER_PREF_PREFIX, network ) ) {
                        if (listAdapter != null) {
                            listAdapter.addWiFi( network );
                        }
                    }
                    // load test
                    // for ( int i = 0; i< 10; i++) {
                    //  listAdapter.addWifi( network );
                    // }

                } else if (listAdapter != null) {
                    // not showing current, and not a new thing, go find the network and update the level
                    // this is O(n), ohwell, that's why showCurrent is the default config.
                    for ( int index = 0; index < listAdapter.getCount(); index++ ) {
                        final Network testNet = listAdapter.getItem(index);
                        if ( testNet.getBssid().equals( network.getBssid() ) ) {
                            testNet.setLevel( result.level );
                        }
                    }
                }

                if ( location != null  ) {
                    // if in fast mode, only add new-for-run stuff to the db queue
                    if ( fastMode && ! added ) {
                        MainActivity.info( "in fast mode, not adding seen-this-run: " + network.getBssid() );
                    } else {
                        // loop for stress-testing
                        // for ( int i = 0; i < 10; i++ ) {
                        boolean matches = false;
                        if (bssidDbMatcher != null) {
                            bssidDbMatcher.reset(network.getBssid());
                            matches = bssidDbMatcher.find();
                        }
                        if (!matches) {
                            dbHelper.addObservation(network, location, added);
                        }
                        // }
                    }
                } else {
                    // no location
                    boolean matches = false;
                    if (bssidDbMatcher != null) {
                        bssidDbMatcher.reset(network.getBssid());
                        matches = bssidDbMatcher.find();
                    }
                    if (!matches) {
                        dbHelper.pendingObservation( network, added );
                    }
                }
            }
        }

        // check if there are more "New" nets
        final long newNetCount = dbHelper.getNewNetworkCount();
        final long newWifiCount = dbHelper.getNewWifiCount();
        final long newNetDiff = newWifiCount - prevNewNetCount;
        prevNewNetCount = newWifiCount;
        // check for "New" cell towers
        final long newCellCount = dbHelper.getNewCellCount();

        if ( ! mainActivity.isMuted() ) {
            final boolean playRun = prefs.getBoolean( ListFragment.PREF_FOUND_SOUND, true );
            final boolean playNew = prefs.getBoolean( ListFragment.PREF_FOUND_NEW_SOUND, true );
            if ( newNetDiff > 0 && playNew ) {
                mainActivity.playNewNetSound();
            }
            else if ( somethingAdded && playRun ) {
                mainActivity.playRunNetSound();
            }
        }

        if ( mainActivity.getPhoneState().isPhoneActive() ) {
            // a phone call is active, make sure we aren't speaking anything
            mainActivity.interruptSpeak();
        }

        // check cell tower info
        final int preCellForRun = runNetworks.size();
        int newCellForRun = 0;
        final Map<String,Network>cellNetworks = recordCellInfo(location);
        if ( cellNetworks != null ) {
            for (String key: cellNetworks.keySet()) {
                final Network cellNetwork = cellNetworks.get(key);
                if (cellNetwork != null) {
                    resultSize++;
                    if (showCurrent && listAdapter != null && FilterMatcher.isOk(ssidMatcher, bssidMatcher, prefs, ListFragment.FILTER_PREF_PREFIX, cellNetwork)) {
                        listAdapter.addCell(cellNetwork);
                    }
                    if (runNetworks.size() > preCellForRun) {
                        newCellForRun++;
                    }
                }
            }
        }

        final int sort = prefs.getInt(ListFragment.PREF_LIST_SORT, SIGNAL_COMPARE);
        Comparator<Network> comparator = signalCompare;
        switch ( sort ) {
            case SIGNAL_COMPARE:
                comparator = signalCompare;
                break;
            case CHANNEL_COMPARE:
                comparator = channelCompare;
                break;
            case CRYPTO_COMPARE:
                comparator = cryptoCompare;
                break;
            case FIND_TIME_COMPARE:
                comparator = findTimeCompare;
                break;
            case SSID_COMPARE:
                comparator = ssidCompare;
                break;
        }
        if (listAdapter != null) {
            listAdapter.sort( comparator );
        }

        final long dbNets = dbHelper.getNetworkCount();
        final long dbLocs = dbHelper.getLocationCount();

        // update stat
        mainActivity.setNetCountUI();

        // set the statics for the map
        ListFragment.lameStatic.runNets = runNetworks.size();
        ListFragment.lameStatic.newNets = newNetCount;
        ListFragment.lameStatic.newWifi = newWifiCount;
        ListFragment.lameStatic.newCells = newCellCount;
        ListFragment.lameStatic.currNets = resultSize;
        ListFragment.lameStatic.preQueueSize = preQueueSize;
        ListFragment.lameStatic.dbNets = dbNets;
        ListFragment.lameStatic.dbLocs = dbLocs;

        // do this if trail is empty, so as soon as we get first gps location it gets triggered
        // and will show up on map
        if ( newWifiForRun > 0 || newCellForRun > 0 || ListFragment.lameStatic.networkCache.isEmpty() ) {
            if ( location == null ) {
                // save for later
                pendingWifiCount += newWifiForRun;
                pendingCellCount += newCellForRun;
                // MainActivity.info("pendingCellCount: " + pendingCellCount);
            }
            else {
                // add any pendings
                // don't go crazy
                if ( pendingWifiCount > 25 ) {
                    pendingWifiCount = 25;
                }
                pendingWifiCount = 0;

                if ( pendingCellCount > 25 ) {
                    pendingCellCount = 25;
                }
                pendingCellCount = 0;
            }
        }

        // info( savedStats );

        // notify
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }

        if ( scanRequestTime <= 0 ) {
            // wasn't set, set to now
            scanRequestTime = now;
        }
        final String status = resultSize + " " + mainActivity.getString(R.string.scanned_in) + " "
                + (now - scanRequestTime) + mainActivity.getString(R.string.ms_short) + ". "
                + mainActivity.getString(R.string.dash_db_queue) + " " + preQueueSize;
        mainActivity.setStatusUI( status );
        // we've shown it, reset it to the nonstop time above, or min_value if nonstop wasn't set.
        scanRequestTime = nonstopScanRequestTime;

        // do lerp if need be
        if ( location == null ) {
            if ( prevGpsLocation != null ) {
                dbHelper.lastLocation( prevGpsLocation );
                // MainActivity.info("set last location for lerping");
            }
        }
        else {
            dbHelper.recoverLocations( location );
        }

        // do distance calcs
        if ( location != null && GPS_PROVIDER.equals( location.getProvider() )
                && location.getAccuracy() <= ListFragment.MIN_DISTANCE_ACCURACY ) {
            if ( prevGpsLocation != null ) {
                float dist = location.distanceTo( prevGpsLocation );
                // info( "dist: " + dist );
                if ( dist > 0f ) {
                    final Editor edit = prefs.edit();
                    edit.putFloat( ListFragment.PREF_DISTANCE_RUN,
                            dist + prefs.getFloat( ListFragment.PREF_DISTANCE_RUN, 0f ) );
                    edit.putFloat( ListFragment.PREF_DISTANCE_TOTAL,
                            dist + prefs.getFloat( ListFragment.PREF_DISTANCE_TOTAL, 0f ) );
                    edit.apply();
                }
            }

            // set for next time
            prevGpsLocation = location;
        }

        if ( somethingAdded && ssidSpeak ) {
            ssidSpeaker.speak();
        }

        final long speechPeriod = prefs.getLong( ListFragment.PREF_SPEECH_PERIOD, MainActivity.DEFAULT_SPEECH_PERIOD );
        if ( speechPeriod != 0 && now - previousTalkTime > speechPeriod * 1000L ) {
            doAnnouncement( preQueueSize, newWifiCount, newCellCount, now );
        }
    }

    public String getNetworkTypeName() {
        TelephonyManager tele = (TelephonyManager) mainActivity.getSystemService( Context.TELEPHONY_SERVICE );
        if ( tele == null ) {
            return null;
        }
        return NETWORK_TYPE_LEGEND.get(tele.getNetworkType());
    }

    private Map<String,Network> recordCellInfo(final Location location) {
        TelephonyManager tele = (TelephonyManager) mainActivity.getSystemService( Context.TELEPHONY_SERVICE );
        Map<String,Network> networks = new HashMap<>();
        if ( tele != null ) {
            try {
                CellLocation currentCell = null;
                //DEBUG: MainActivity.info("SIM State: "+tele.getSimState() + "("+getNetworkTypeName()+")");
                currentCell = tele.getCellLocation();
                if (currentCell != null) {
                    Network currentNetwork = handleSingleCellLocation(currentCell, tele, location);
                    if (currentNetwork != null) {
                        networks.put(currentNetwork.getBssid(), currentNetwork);
                    }
                }

                if (Build.VERSION.SDK_INT >= 17) { // we can survey cells
                    List<CellInfo> infos = tele.getAllCellInfo();
                    if (null != infos) {
                        for (final CellInfo cell : infos) {
                            Network network = handleSingleCellInfo(cell, tele, location);
                            if (null != network) {
                                if (networks.containsKey(network.getBssid())) {
                                    //DEBUG: MainActivity.info("matching network already in map: " + network.getBssid());
                                    Network n = networks.get(network.getBssid());
                                    //TODO merge to improve data instead of replace?
                                    networks.put(network.getBssid(), network);
                                } else {
                                    networks.put(network.getBssid(), network);
                                }
                            }
                        }
                    }
                } else {
                    //TODO: handle multiple SIMs in early revs?
                }
                //ALIBI: haven't been able to find a circumstance where there's anything but garbage in these.
                //  should be an alternative to getAllCellInfo above for older phones, but oly dBm looks valid


                /*List<NeighboringCellInfo> list = tele.getNeighboringCellInfo();
                if (null != list) {
                    for (final NeighboringCellInfo cell : list) {
                        //networks.put(
                        handleSingleNeighboringCellInfo(cell, tele, location);
                        //);
                    }
                }*/
            } catch (SecurityException sex) {
                MainActivity.warn("unable to scan cells due to permission issue: ", sex);
            } catch (NullPointerException ex) {
                MainActivity.warn("NPE on cell scan: ", ex);
            }
        }
        return networks;
    }

    private Network handleSingleCellInfo(final CellInfo cellInfo, final TelephonyManager tele, final Location location) {
        if (cellInfo == null) {
            MainActivity.info("null cellInfo");
            // ignore
        } else {
            if (MainActivity.DEBUG_CELL_DATA) {
                MainActivity.info("cell: " + cellInfo + " class: " + cellInfo.getClass().getCanonicalName());
            }
            switch (cellInfo.getClass().getSimpleName()) {
                case "CellInfoCdma":
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        return handleSingleCdmaInfo(((CellInfoCdma) (cellInfo)), tele , location);
                    }
                    break;
                case "CellInfoGsm":
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        return handleSingleGsmInfo(((CellInfoGsm) (cellInfo)), tele, location);
                    }
                    break;
                case "CellInfoLte":
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
                        return handleSingleLteInfo(((CellInfoLte)(cellInfo)), tele, location);
                    }
                    break;
                case "CellInfoWcdma":
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) { //WHYYYYYY?
                        return handleSingleWcdmaInfo((CellInfoWcdma)(cellInfo), tele, location);
                    }
                    break;
                default:
                    MainActivity.warn("Unknown cell case: "+cellInfo.getClass().getSimpleName());
                    break;
            }
        }
        return null;
    }

    /**
     * no test environment to implement this, but the handleCellInfo methods should work to complete it.
     * @param cellInfo
     * @param tele
     * @param location
     * @return
     */
    @Deprecated
    private Network handleSingleNeighboringCellInfo(final NeighboringCellInfo cellInfo, final TelephonyManager tele, final Location location) {
        //noinspection StatementWithEmptyBody
        if (null == cellInfo) {
            // ignore
        } else {
            if (MainActivity.DEBUG_CELL_DATA) {
                MainActivity.info("NeighboringCellInfo:" +
                        "\n\tCID: " + cellInfo.getCid() +
                        "\n\tLAC: " + cellInfo.getLac() +
                        "\n\tType: " + cellInfo.getNetworkType() +
                        "\n\tPsc: " + cellInfo.getPsc() +
                        "\n\tRSSI: " + cellInfo.getRssi());
            }
            switch (cellInfo.getNetworkType()) {
                case TelephonyManager.NETWORK_TYPE_GPRS:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_EDGE:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_UMTS:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                    //TODO!!!
                    break;
                case TelephonyManager.NETWORK_TYPE_HSPA:
                    //TODO!!!
                    break;
                default:
                    //TODO!!!
                    break;
            }
        }
        return null; //TODO:
    }

    private Network handleSingleCellLocation(final CellLocation cellLocation,
                                             final TelephonyManager tele, final Location location) {
        String bssid = null;
        NetworkType type = null;
        Network network = null;
        String ssid = null;

        //noinspection StatementWithEmptyBody
        if ( cellLocation == null ) {
            // ignore
        } else if ( cellLocation.getClass().getSimpleName().equals("CdmaCellLocation") ) {
            try {
                final int systemId = ((CdmaCellLocation) cellLocation).getSystemId();
                final int networkId = ((CdmaCellLocation) cellLocation).getNetworkId();
                final int baseStationId = ((CdmaCellLocation) cellLocation).getBaseStationId();
                if ( systemId > 0 && networkId >= 0 && baseStationId >= 0 ) {
                    bssid = systemId + "_" + networkId + "_" + baseStationId;
                    type = NetworkType.CDMA;
                }
                //TODO: not sure if there's anything else we can do here
                ssid = tele.getNetworkOperatorName();
            } catch ( Exception ex ) {
                MainActivity.error("CDMA reflection exception: " + ex);
            }
        } else if ( cellLocation instanceof GsmCellLocation ) {
            GsmCellLocation gsmCellLocation = (GsmCellLocation) cellLocation;
            final String operatorCode = tele.getNetworkOperator();
            if ( gsmCellLocation.getLac() >= 0 && gsmCellLocation.getCid() >= 0) {
                bssid = tele.getNetworkOperator() + "_" + gsmCellLocation.getLac() + "_" + gsmCellLocation.getCid();
                ssid = getOperatorName(tele.getNetworkOperator());
                //DEBUG: MainActivity.info("GSM Operator name: "+ ssid + " vs TM: "+ tele.getNetworkOperatorName());
                type = NetworkType.GSM;
            }
            if (operatorCode == null || operatorCode.isEmpty()) {
                return null;
            }
        } else {
            MainActivity.warn("Unhandled CellLocation type: "+cellLocation.getClass().getSimpleName());
        }

        if ( bssid != null ) {
            final String networkType = getNetworkTypeName();
            final String capabilities = networkType + ";" + tele.getNetworkCountryIso();

            int strength = 0;
            PhoneState phoneState = mainActivity.getPhoneState();
            if (phoneState != null) {
                strength = phoneState.getStrength();
            }

            if ( NetworkType.GSM.equals(type) ) {
                // never seems to work well in practice
                strength = gsmDBmMagicDecoderRing( strength );
            }

            if (MainActivity.DEBUG_CELL_DATA) {
                MainActivity.info("bssid: " + bssid);
                MainActivity.info("strength: " + strength);
                MainActivity.info("ssid: " + ssid);
                MainActivity.info("capabilities: " + capabilities);
                MainActivity.info("networkType: " + networkType);
                MainActivity.info("location: " + location);
            }

            final ConcurrentLinkedHashMap<String,Network> networkCache = MainActivity.getNetworkCache();

            final boolean newForRun = runNetworks.add( bssid );

            network = networkCache.get( bssid );
            if ( network == null ) {
                network = new Network( bssid, ssid, 0, capabilities, strength, type );
                networkCache.put( network.getBssid(), network );
            } else {
                network.setLevel(strength);
            }

            if ( location != null && (newForRun || network.getLatLng() == null) ) {
                // set the LatLng for mapping
                final LatLng LatLng = new LatLng( location.getLatitude(), location.getLongitude() );
                network.setLatLng( LatLng );
            }

            if ( location != null ) {
                dbHelper.addObservation(network, location, newForRun);
            }
        }
        return network;
    }

    /**
     * This was named RSSI - but I think it's more accurately dBm. Also worth noting that ALL the
     * SignalStrength changes we've received in PhoneState for GSM networks have been resulting in
     * "99" -> -113 in every measurable case on all hardware in testing.
     * @param strength
     * @return
     */
    private int gsmDBmMagicDecoderRing( int strength ) {
        int retval;
        if ( strength == 99 ) {
            // unknown
            retval = CELL_MIN_STRENGTH;
        }
        else {
            //  0        -113 dBm or less
            //  1        -111 dBm
            //  2...30   -109... -53 dBm
            //  31        -51 dBm or greater
            //  99 not known or not detectable
            retval = strength * 2 + CELL_MIN_STRENGTH;
        }
        //DEBUG: MainActivity.info("strength: " + strength + " dBm: " + retval);
        return retval;
    }

    private void doAnnouncement( int preQueueSize, long newWifiCount, long newCellCount, long now ) {
        final SharedPreferences prefs = mainActivity.getSharedPreferences( ListFragment.SHARED_PREFS, 0 );
        StringBuilder builder = new StringBuilder();

        if ( mainActivity.getGPSListener().getLocation() == null && prefs.getBoolean( ListFragment.PREF_SPEECH_GPS, true ) ) {
            builder.append(mainActivity.getString(R.string.tts_no_gps_fix)).append(", ");
        }

        // run, new, queue, miles, time, battery
        if ( prefs.getBoolean( ListFragment.PREF_SPEAK_RUN, true ) ) {
            builder.append(mainActivity.getString(R.string.run)).append(" ")
                    .append(runNetworks.size()).append( ", " );
        }
        if ( prefs.getBoolean( ListFragment.PREF_SPEAK_NEW_WIFI, true ) ) {
            builder.append(mainActivity.getString(R.string.tts_new_wifi)).append(" ")
                    .append(newWifiCount).append( ", " );
        }
        if ( prefs.getBoolean( ListFragment.PREF_SPEAK_NEW_CELL, true ) ) {
            builder.append(mainActivity.getString(R.string.tts_new_cell)).append(" ")
                    .append(newCellCount).append( ", " );
        }
        if ( preQueueSize > 0 && prefs.getBoolean( ListFragment.PREF_SPEAK_QUEUE, true ) ) {
            builder.append(mainActivity.getString(R.string.tts_queue)).append(" ")
                    .append(preQueueSize).append( ", " );
        }
        if ( prefs.getBoolean( ListFragment.PREF_SPEAK_MILES, true ) ) {
            final float dist = prefs.getFloat( ListFragment.PREF_DISTANCE_RUN, 0f );
            final String distString = DashboardFragment.metersToString( numberFormat1, mainActivity, dist, false );
            builder.append(mainActivity.getString(R.string.tts_from)).append(" ")
                    .append(distString).append( ", " );
        }
        if ( prefs.getBoolean( ListFragment.PREF_SPEAK_TIME, true ) ) {
            String time = timeFormat.format( new Date() );
            // time is hard to say.
            time = time.replace(" 00", " " + mainActivity.getString(R.string.tts_o_clock));
            time = time.replace(" 0", " " + mainActivity.getString(R.string.tts_o) +  " ");
            builder.append( time ).append( ", " );
        }
        final int batteryLevel = mainActivity.getBatteryLevelReceiver().getBatteryLevel();
        if ( batteryLevel >= 0 && prefs.getBoolean( ListFragment.PREF_SPEAK_BATTERY, true ) ) {
            builder.append(mainActivity.getString(R.string.tts_battery)).append(" ").append(batteryLevel).append(" ").append(mainActivity.getString(R.string.tts_percent)).append(", ");
        }

        final String speak = builder.toString();
        MainActivity.info( "speak: " + speak );
        if (! "".equals(speak)) {
            mainActivity.speak( builder.toString() );
        }
        previousTalkTime = now;
    }

    public void setupWifiTimer( final boolean turnedWifiOn ) {
        MainActivity.info( "create wifi timer" );
        if ( wifiTimer == null ) {
            wifiTimer = new Handler();
            final Runnable mUpdateTimeTask = new Runnable() {
                @Override
                public void run() {
                    // make sure the app isn't trying to finish
                    if ( ! mainActivity.isFinishing() ) {
                        // info( "timer start scan" );
                        doWifiScan();
                        if ( scanRequestTime <= 0 ) {
                            scanRequestTime = System.currentTimeMillis();
                        }
                        long period = getScanPeriod();
                        // check if set to "continuous"
                        if ( period == 0L ) {
                            // set to default here, as a scan will also be requested on the scan result listener
                            period = MainActivity.SCAN_DEFAULT;
                        }
                        // info("wifitimer: " + period );
                        wifiTimer.postDelayed( this, period );
                    }
                    else {
                        MainActivity.info( "finishing timer" );
                    }
                }
            };
            wifiTimer.removeCallbacks( mUpdateTimeTask );
            wifiTimer.postDelayed( mUpdateTimeTask, 100 );

            if ( turnedWifiOn ) {
                MainActivity.info( "not immediately running wifi scan, since it was just turned on"
                        + " it will block for a few seconds and fail anyway");
            }
            else {
                MainActivity.info( "start first wifi scan");
                // starts scan, sends event when done
                final boolean scanOK = doWifiScan();
                if ( scanRequestTime <= 0 ) {
                    scanRequestTime = System.currentTimeMillis();
                }
                MainActivity.info( "startup finished. wifi scanOK: " + scanOK );
            }
        }
    }

    public long getScanPeriod() {
        final SharedPreferences prefs = mainActivity.getSharedPreferences( ListFragment.SHARED_PREFS, 0 );

        String scanPref = ListFragment.PREF_SCAN_PERIOD;
        long defaultRate = MainActivity.SCAN_DEFAULT;
        // if over 5 mph
        Location location = null;
        final GPSListener gpsListener = mainActivity.getGPSListener();
        if (gpsListener != null) {
            location = gpsListener.getLocation();
        }
        if ( location != null && location.getSpeed() >= 2.2352f ) {
            scanPref = ListFragment.PREF_SCAN_PERIOD_FAST;
            defaultRate = MainActivity.SCAN_FAST_DEFAULT;
        }
        else if ( location == null || location.getSpeed() < 0.1f ) {
            scanPref = ListFragment.PREF_SCAN_PERIOD_STILL;
            defaultRate = MainActivity.SCAN_STILL_DEFAULT;
        }
        return prefs.getLong( scanPref, defaultRate );
    }

    public void scheduleScan() {
        wifiTimer.post(new Runnable() {
            @Override
            public void run() {
                doWifiScan();
            }
        });
    }

    /**
     * only call this from a Handler
     * @return true if startScan success
     */
    private boolean doWifiScan() {
        // MainActivity.info("do wifi scan. lastScanTime: " + lastScanResponseTime);
        final WifiManager wifiManager = (WifiManager) mainActivity.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        boolean success = false;

        if (mainActivity.isScanning()) {
            if ( ! scanInFlight ) {
                try {
                    success = wifiManager.startScan();
                }
                catch (Exception ex) {
                    MainActivity.warn("exception starting scan: " + ex, ex);
                }
                if ( success ) {
                    scanInFlight = true;
                }
            }

            // schedule a bluetooth scan
            mainActivity.bluetoothScan();

            final long now = System.currentTimeMillis();
            if ( lastScanResponseTime < 0 ) {
                // use now, since we made a request
                lastScanResponseTime = now;
            } else {
                final long sinceLastScan = now - lastScanResponseTime;
                final SharedPreferences prefs = mainActivity.getSharedPreferences( ListFragment.SHARED_PREFS, 0 );
                final long resetWifiPeriod = prefs.getLong(
                        ListFragment.PREF_RESET_WIFI_PERIOD, MainActivity.DEFAULT_RESET_WIFI_PERIOD );

                if ( resetWifiPeriod > 0 && sinceLastScan > resetWifiPeriod ) {
                    MainActivity.warn("Time since last scan: " + sinceLastScan + " milliseconds");
                    if ( now - lastWifiUnjamTime > resetWifiPeriod ) {
                        final boolean disableToast = prefs.getBoolean(ListFragment.PREF_DISABLE_TOAST, false);
                        if (!disableToast &&  null != mainActivity && !mainActivity.isFinishing()) {
                            if (null != mainActivity && !mainActivity.isFinishing()) {
                                WiGLEToast.showOverActivity(mainActivity, R.string.error_general, mainActivity.getString(R.string.wifi_jammed));
                            }
                        }
                        scanInFlight = false;
                        try {
                            wifiManager.setWifiEnabled(false);
                            wifiManager.setWifiEnabled(true);
                        }
                        catch (SecurityException ex) {
                            MainActivity.info("exception resetting wifi: " + ex, ex);
                        }
                        lastWifiUnjamTime = now;
                        if (prefs.getBoolean(ListFragment.PREF_SPEAK_WIFI_RESTART, true)) {
                            mainActivity.speak(mainActivity.getString(R.string.wifi_restart_1) + " "
                                    + (sinceLastScan / 1000L) + " " + mainActivity.getString(R.string.wifi_restart_2));
                        }
                    }
                }
            }
        }
        else {
            // scanning is off. since we're the only timer, update the UI
            mainActivity.setNetCountUI();
            mainActivity.setLocationUI();
            mainActivity.setStatusUI("Scanning Turned Off" );
            // keep the scan times from getting huge
            scanRequestTime = System.currentTimeMillis();
            // reset this
            lastScanResponseTime = Long.MIN_VALUE;
        }

        // battery kill
        if ( ! mainActivity.isTransferring() ) {
            final SharedPreferences prefs = mainActivity.getSharedPreferences( ListFragment.SHARED_PREFS, 0 );
            long batteryKill = prefs.getLong(
                    ListFragment.PREF_BATTERY_KILL_PERCENT, MainActivity.DEFAULT_BATTERY_KILL_PERCENT);

            if ( mainActivity.getBatteryLevelReceiver() != null ) {
                final int batteryLevel = mainActivity.getBatteryLevelReceiver().getBatteryLevel();
                final int batteryStatus = mainActivity.getBatteryLevelReceiver().getBatteryStatus();
                // MainActivity.info("batteryStatus: " + batteryStatus);
                // give some time since starting up to change this configuration
                if ( batteryKill > 0 && batteryLevel > 0 && batteryLevel <= batteryKill
                        && batteryStatus != BatteryManager.BATTERY_STATUS_CHARGING
                        && (System.currentTimeMillis() - constructionTime) > 30000L) {
                    if (null != mainActivity) {
                        final String text = mainActivity.getString(R.string.battery_at) + " " + batteryLevel + " "
                            + mainActivity.getString(R.string.battery_postfix);
                        if (!mainActivity.isFinishing()) {
                            WiGLEToast.showOverActivity(mainActivity, R.string.error_general, text);
                        }
                        MainActivity.warn("low battery, shutting down");
                        mainActivity.speak(text);
                        mainActivity.finishSoon(4000L, false);
                    }
                }
            }
        }

        return success;
    }

    private Network handleSingleCdmaInfo(final CellInfoCdma cellInfo, final TelephonyManager tele, final Location location) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            CellIdentityCdma cellIdentC = cellInfo.getCellIdentity();
            CellSignalStrengthCdma cellStrengthC = ((CellInfoCdma) (cellInfo)).getCellSignalStrength();

            final int bssIdInt = cellIdentC.getBasestationId();
            final int netIdInt = cellIdentC.getNetworkId();
            final int systemIdInt = cellIdentC.getSystemId();

            if ((Integer.MAX_VALUE == bssIdInt) || (Integer.MAX_VALUE == netIdInt) || (Integer.MAX_VALUE == systemIdInt)) {
                MainActivity.info("Discarding CDMA cell with invalid ID");
                return null;
            }

            final String networkKey = systemIdInt + "_" + netIdInt + "_" + bssIdInt;
            final int dBmLevel = cellStrengthC.getDbm();
            if (MainActivity.DEBUG_CELL_DATA) {

                String res = "CDMA Cell:" +
                        "\n\tBSSID:" + bssIdInt +
                        "\n\tNet ID:" + netIdInt +
                        "\n\tSystem ID:" + systemIdInt +
                        "\n\tNetwork Key: " + networkKey;

                res += "\n\tLat: " + new Double(cellIdentC.getLatitude()) / 4.0d / 60.0d / 60.0d;
                res += "\n\tLon: " + new Double(cellIdentC.getLongitude()) / 4.0d / 60.0d / 60.0d;
                res += "\n\tSignal: " + cellStrengthC.getCdmaLevel();

                int rssi = cellStrengthC.getEvdoDbm() != 0 ? cellStrengthC.getEvdoDbm() : cellStrengthC.getCdmaDbm();
                res += "\n\tRSSI: " + rssi;

                final int asuLevel = cellStrengthC.getAsuLevel();

                res += "\n\tSSdBm: " + dBmLevel;
                res += "\n\tSSasu: " + asuLevel;
                res += "\n\tEVDOdBm: " + cellStrengthC.getEvdoDbm();
                res += "\n\tCDMAdBm: " + cellStrengthC.getCdmaDbm();
                MainActivity.info(res);
            }
            //TODO: don't see any way to get CDMA channel from current CellInfoCDMA/CellIdentityCdma
            //  references http://niviuk.free.fr/cdma_band.php
            return addOrUpdateCell(networkKey,
                    /*TODO: can we improve on this?*/ tele.getNetworkOperator(),
                    0, "CDMA", dBmLevel, NetworkType.typeForCode("C"), location);

        }
        return null;
    }

    private Network handleSingleGsmInfo(final CellInfoGsm cellInfo, final TelephonyManager tele, final Location location) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            CellIdentityGsm cellIdentG = ((CellInfoGsm)(cellInfo)).getCellIdentity();
            CellSignalStrengthGsm cellStrengthG = ((CellInfoGsm)(cellInfo)).getCellSignalStrength();
            int mcc = 0;
            int mnc = 0;
            final int cidInt = cellIdentG.getCid();
            final int lacInt = cellIdentG.getLac();

            if ((Integer.MAX_VALUE == cidInt) || (Integer.MAX_VALUE == lacInt)) {
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    //DEBUG: MainActivity.info("Discarding GSM cell with invalid ID for ARFCN: " + cellIdentG.getArfcn());
                } else {
                    //DEBUG: MainActivity.info("Discarding GSM cell with invalid ID");
                }
                return null;
            }

            String operator = null;

            if (android.os.Build.VERSION.SDK_INT >= 28) {
                // mcc = Integer.parseInt(cellIdentG.getMccString());
                // mnc = Integer.parseInt(cellIdentG.getMncString());
                // operator = cellIdentG.getMobileNetworkOperator();
            } else {
                mcc = cellIdentG.getMcc();
                mnc = cellIdentG.getMnc();
                operator = mcc+""+mnc;
            }

            final String networkKey = mcc+""+mnc+"_"+lacInt+"_"+cidInt;
            int dBmlevel = cellStrengthG.getDbm();
            int fcn = 0;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                fcn = cellIdentG.getArfcn() != Integer.MAX_VALUE ? cellIdentG.getArfcn() : 0;
            }

            if (MainActivity.DEBUG_CELL_DATA) {
                String res = "GSM Cell:" +
                        "\n\tCID: " + cidInt +
                        "\n\tLAC: " + lacInt +
                        "\n\tPSC: " + cellIdentG.getPsc() +
                        "\n\tMCC: " + mcc +
                        "\n\tMNC: " + mnc +
                        "\n\tNetwork Key: " + networkKey +
                        "\n\toperator: " + operator +
                        "\n\tARFCN: " + fcn;

                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    res += "\n\tBSIC: " + cellIdentG.getBsic();
                }

                int asulevel = cellStrengthG.getAsuLevel();

                res += "\n\tSignal: " + cellStrengthG.getLevel();
                res += "\n\tDBM: " + dBmlevel;

                res += "\n\tASUL: " + asulevel;
                MainActivity.info(res);
            }
            return  addOrUpdateCell(networkKey, operator, fcn, "GSM",
                    dBmlevel, NetworkType.typeForCode("G"), location);
        }

        return null;
    }

    private Network handleSingleLteInfo(final CellInfoLte cellInfo, final TelephonyManager tele, final Location location) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR1) {
            CellIdentityLte cellIdentL = cellInfo.getCellIdentity();
            CellSignalStrengthLte cellStrengthL = ((CellInfoLte)(cellInfo)).getCellSignalStrength();

            final int mnc = android.os.Build.VERSION.SDK_INT >= 28?Integer.MAX_VALUE/*TODO: Integer.parseInt(cellIdentL.getMncString())*/:cellIdentL.getMnc();
            final int mcc = android.os.Build.VERSION.SDK_INT >= 28?Integer.MAX_VALUE/*TODO: Integer.parseInt(cellIdentL.getMccString())*/:cellIdentL.getMcc();
            final int ciInt = cellIdentL.getCi();
            final int tacInt = cellIdentL.getTac();

            if ((Integer.MAX_VALUE == ciInt) || (Integer.MAX_VALUE == mcc) || (Integer.MAX_VALUE == mnc) || (Integer.MAX_VALUE == tacInt)) {
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    //DEBUG: MainActivity.info("Discarding LTE cell with invalid ID for EARFCN: " + cellIdentL.getEarfcn());
                } else {
                    //DEBUG: MainActivity.info("Discarding LTE cell with invalid ID");
                }
                return null;
            }

            String operator = null;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                //TODO: operator = cellIdentL.getMobileNetworkOperator();
            } else {
                operator = mcc+""+mnc;
            }
            final String networkKey = mcc+""+mnc+"_"+tacInt+"_"+ciInt;
            int dBmlevel = cellStrengthL.getDbm();
            int fcn = 0;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                fcn = cellIdentL.getEarfcn() != Integer.MAX_VALUE ?
                        cellIdentL.getEarfcn():0;
            }

            if (MainActivity.DEBUG_CELL_DATA) {
                String res = "LTE Cell: " +
                        "\n\tCI: " + ciInt +
                        "\n\tPCI: " + cellIdentL.getPci() +
                        "\n\tTAC: " + tacInt +
                        "\n\tMCC: " + mcc +
                        "\n\tMNC: " + mnc +
                        "\n\tNetwork Key: " + networkKey +
                        "\n\toperator: " + operator +
                        "\n\tEARFCN:" + fcn;

                if (Build.VERSION.SDK_INT >= 28) {
                    //TODO: res += "\n\tBandwidth: "+cellIdentL.getBandwidth()
                }

                int asulevel = cellStrengthL.getAsuLevel();

                res += "\n\tlevel:" + cellStrengthL.getLevel();
                res += "\n\tDBM: " + dBmlevel;
                res += "\n\tASUL: " + asulevel;
                if (Build.VERSION.SDK_INT >= 26) {
                    res += "\n\tRSRP:" + cellStrengthL.getRsrp() +
                            "\n\tRSRQ:" + cellStrengthL.getRsrq() +
                            "\n\tCQI:" + cellStrengthL.getCqi() +
                            "\n\tRSSNR:" + cellStrengthL.getRssnr();
                }
                MainActivity.info(res);
            }

            return addOrUpdateCell(networkKey, operator, fcn, "LTE",
                    dBmlevel, NetworkType.typeForCode("L"), location);
        }
        return null;
    }

    private Network handleSingleWcdmaInfo(final CellInfoWcdma cellInfo, final TelephonyManager tele, final Location location) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) { //WHYYYYYY?
            CellIdentityWcdma cellIdentW = cellInfo.getCellIdentity();
            CellSignalStrengthWcdma cellStrengthW = ((CellInfoWcdma)(cellInfo)).getCellSignalStrength();

            final int cidInt = cellIdentW.getCid();
            final int lacInt = cellIdentW.getLac();
            final int mnc = android.os.Build.VERSION.SDK_INT >= 28?Integer.MAX_VALUE/*TODO: Integer.parseInt(cellIdentW.getMncString())*/:cellIdentW.getMnc();
            final int mcc = android.os.Build.VERSION.SDK_INT >= 28?Integer.MAX_VALUE/*TODO: Integer.parseInt(cellIdentW.getMccString())*/:cellIdentW.getMcc();


            if ((Integer.MAX_VALUE == cidInt) || (Integer.MAX_VALUE == lacInt)) {
                if (android.os.Build.VERSION.SDK_INT >= 24) {
                    //DEBUG: MainActivity.info("Discarding WCDMA cell with invalid ID for UARFCN: "+cellIdentW.getUarfcn());
                } else {
                    //DEBUG: MainActivity.info("Discarding WCDMA cell with invalid ID");
                }
                return null;
            }

            String operator = null;
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                //TODO: operator = cellIdentW.getMobileNetworkOperator();
            } else {
                operator = mcc+""+mnc;
            }

            final String networkKey = mcc+""+mnc+"_"+lacInt+"_"+cidInt;
            int dBmlevel = cellStrengthW.getDbm();
            int fcn = 0;
            if (android.os.Build.VERSION.SDK_INT >= 24) {
                fcn = (cellIdentW.getUarfcn() != Integer.MAX_VALUE) ?
                        cellIdentW.getUarfcn():0;
            }

            if (MainActivity.DEBUG_CELL_DATA) {
                String res = "WCDMA Cell:" +
                        "\n\tCI: " + cidInt +
                        "\n\tLAC: " + lacInt +
                        "\n\tMCC: " + mcc +
                        "\n\tMNC: " + mnc +
                        "\n\tNetwork Key: " + networkKey +
                        "\n\toperator: " + operator +
                        "\n\tUARFCN:" + fcn;

                int asulevel = cellStrengthW.getAsuLevel();

                res += "\n\tPSC:" + cellIdentW.getPsc();
                res += "\n\tlevel:" + cellStrengthW.getLevel();
                res += "\n\tASUL: " + asulevel;
                res += "\n\tdBm:" + dBmlevel;
                MainActivity.info(res);
            }

            return addOrUpdateCell(networkKey, operator, fcn, "WCDMA",
                dBmlevel, NetworkType.typeForCode("D"), location);
        }
        return null;
    }

    private Network addOrUpdateCell(final String bssid, final String operator,
                                    final int frequency, final String networkTypeName,
                                    final int strength, final NetworkType type,
                                    final Location location) {

        final String capabilities = networkTypeName + ";" + operator;

        final ConcurrentLinkedHashMap<String,Network> networkCache = MainActivity.getNetworkCache();
        final boolean newForRun = runNetworks.add( bssid );

        Network network = networkCache.get( bssid );

        final String operatorName = getOperatorName(operator);

        if ( network == null ) {
            network = new Network( bssid, operatorName, frequency, capabilities, (Integer.MAX_VALUE == strength) ? CELL_MIN_STRENGTH : strength, type );
            networkCache.put( network.getBssid(), network );
        } else {
            network.setLevel( (Integer.MAX_VALUE == strength) ? CELL_MIN_STRENGTH : strength);
            network.setFrequency(frequency);
        }

        if ( location != null && (newForRun || network.getLatLng() == null) ) {
            // set the LatLng for mapping
            final LatLng LatLng = new LatLng( location.getLatitude(), location.getLongitude() );
            network.setLatLng( LatLng );
        }

        if ( location != null ) {
            dbHelper.addObservation(network, location, newForRun);
        }
        //ALIBI: allows us to run in conjunction with current-carrier detection
        return network;
    }

    /**
     * Map the 5-6 digit operator code against the database of operator names
     * @param operatorCode
     * @return
     */
    private String getOperatorName(final String operatorCode) {
        //ALIBI: MCC is always 3 chars, MNC may be 2 or 3.
        if (null != operatorCode && operatorCode.length() >= 5) {


            final String mnc = operatorCode.substring(3, operatorCode.length());
            final String mcc = operatorCode.substring(0, 3);
            //DEBUG:  MainActivity.info("Operator MCC: "+mcc+" MNC: "+mnc);

            Map<String, String> mccMap = OPERATOR_CACHE.get(mcc);;
            if (null == mccMap) {
                mccMap = new HashMap<>();
                OPERATOR_CACHE.put(mcc, mccMap);
            }

            String operator = mccMap.get(mnc);
            if (null != operator) {
                return operator;
            }

            MainActivity.State s = this.mainActivity.getState();
            if (null != s) {
                operator = s.mxcDbHelper.networkNameForMccMnc(mcc,mnc);
                mccMap.put(mnc, operator);
                return operator;
            }
        }
        return null;
    }
}
