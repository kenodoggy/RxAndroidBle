package com.polidea.rxandroidble.internal.connection

import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.support.annotation.NonNull
import com.polidea.rxandroidble.*
import com.polidea.rxandroidble.exceptions.*
import com.polidea.rxandroidble.internal.operations.OperationsProviderImpl
import com.polidea.rxandroidble.internal.operations.RxBleRadioOperationReadRssi
import com.polidea.rxandroidble.internal.util.ByteAssociation
import java.util.concurrent.TimeUnit
import com.polidea.rxandroidble.internal.util.MockOperationTimeoutConfiguration
import rx.Observable
import rx.Scheduler
import rx.observers.TestSubscriber
import rx.schedulers.TestScheduler
import rx.subjects.BehaviorSubject
import rx.subjects.PublishSubject
import spock.lang.Specification
import spock.lang.Unroll

import static rx.Observable.from
import static rx.Observable.just

class RxBleConnectionTest extends Specification {

    public static final CHARACTERISTIC_UUID = UUID.fromString("f301f518-5414-471c-8a7b-2ef6d1b7373d")
    public static final CHARACTERISTIC_INSTANCE_ID = 1
    public static final OTHER_UUID = UUID.fromString("ab906173-5daa-4d6b-8604-c2be69122d57")
    public static final OTHER_INSTANCE_ID = 2
    public static final byte[] NOT_EMPTY_DATA = [1, 2, 3] as byte[]
    public static final byte[] OTHER_DATA = [2, 2, 3] as byte[]
    public static final int EXPECTED_RSSI_VALUE = 5
    def flatRadio = new FlatRxBleRadio()
    def gattCallback = Mock RxBleGattCallback
    def bluetoothGattMock = Mock BluetoothGatt
    def mockServiceDiscoveryManager = Mock ServiceDiscoveryManager
    def testScheduler = new TestScheduler()
    def timeoutConfig = new MockOperationTimeoutConfiguration(testScheduler)
    def operationsProviderMock = new OperationsProviderImpl(gattCallback, bluetoothGattMock, timeoutConfig, testScheduler,
            testScheduler, { new RxBleRadioOperationReadRssi(gattCallback, bluetoothGattMock, timeoutConfig) })
    def notificationAndIndicationManagerMock = Mock NotificationAndIndicationManager
    def descriptorWriterMock = Mock DescriptorWriter
    def objectUnderTest = new RxBleConnectionImpl(flatRadio, gattCallback, bluetoothGattMock, mockServiceDiscoveryManager,
            notificationAndIndicationManagerMock, descriptorWriterMock, operationsProviderMock,
            { new LongWriteOperationBuilderImpl(flatRadio, { 20 }, Mock(RxBleConnection)) }, testScheduler
    )
    def connectionStateChange = BehaviorSubject.create()
    def TestSubscriber testSubscriber

    def setup() {
        testSubscriber = new TestSubscriber()
        gattCallback.getOnConnectionStateChange() >> connectionStateChange
    }

    def "should proxy all calls to .discoverServices() to ServiceDiscoveryManager with proper timeouts"() {

        when:
        invokationClosure.call(objectUnderTest)

        then:
        1 * mockServiceDiscoveryManager.getDiscoverServicesObservable(timeout, timeoutTimeUnit) >> Observable.empty()

        where:
        timeout | timeoutTimeUnit  | invokationClosure
        20      | TimeUnit.SECONDS | { RxBleConnection objectUnderTest -> objectUnderTest.discoverServices() }
        2       | TimeUnit.HOURS   | { RxBleConnection objectUnderTest -> objectUnderTest.discoverServices(2, TimeUnit.HOURS) }
    }

    @Unroll
    def "should emit BleGattCannotStartException if failed to start writing characteristic"() {
        given:
        // for third setupWriteClosure
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: OTHER_DATA)
        shouldGattContainServiceWithCharacteristic(characteristic, CHARACTERISTIC_UUID)

        gattCallback.getOnCharacteristicWrite() >> PublishSubject.create()
        shouldFailStartingCharacteristicWrite()

        when:
        setupWriteClosure.call(objectUnderTest, characteristic, OTHER_DATA).subscribe(testSubscriber)

        then:
        testSubscriber.assertError BleGattCannotStartException
        testSubscriber.assertError { it.bleGattOperationType == BleGattOperationType.CHARACTERISTIC_WRITE }

        where:
        setupWriteClosure << [
                writeCharacteristicCharacteristicDeprecatedClosure,
                writeCharacteristicCharacteristicClosure,
                writeCharacteristicUuidClosure
        ]
    }

    @Unroll
    def "should emit BleGattCannotStartException if failed to start reading characteristic"() {
        given:
        def characteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: OTHER_DATA)
        shouldGattContainServiceWithCharacteristic(characteristic, CHARACTERISTIC_UUID)
        gattCallback.getOnCharacteristicRead() >> PublishSubject.create()
        shouldFailStartingCharacteristicRead()

        when:
        setupReadClosure.call(objectUnderTest, characteristic).subscribe(testSubscriber)

        then:
        testSubscriber.assertError BleGattCannotStartException
        testSubscriber.assertError { it.bleGattOperationType == BleGattOperationType.CHARACTERISTIC_READ }

        where:
        setupReadClosure << [
                readCharacteristicUuidClosure,
                readCharacteristicCharacteristicClosure
        ]
    }

    def "should emit BleGattCannotStartException if failed to start retrieving rssi"() {
        given:
        shouldReturnStartingStatusAndEmitRssiValueThroughCallback { false }

        when:
        objectUnderTest.readRssi().subscribe(testSubscriber)

        then:
        testSubscriber.assertError BleGattCannotStartException
        testSubscriber.assertError { it.bleGattOperationType == BleGattOperationType.READ_RSSI }
    }

    def "should emit BleCharacteristicNotFoundException during read operation if no services were found"() {
        given:
        shouldDiscoverServices([])

        when:
        objectUnderTest.readCharacteristic(CHARACTERISTIC_UUID).subscribe(testSubscriber)

        then:
        testSubscriber.assertError BleCharacteristicNotFoundException
    }

    def "should emit BleCharacteristicNotFoundException during read operation if characteristic was not found"() {
        given:
        def service = Mock BluetoothGattService
        shouldDiscoverServices([service])
        service.getCharacteristic(_) >> null

        when:
        objectUnderTest.readCharacteristic(CHARACTERISTIC_UUID).subscribe(testSubscriber)

        then:
        testSubscriber.assertError BleCharacteristicNotFoundException
        testSubscriber.assertError { it.charactersisticUUID == CHARACTERISTIC_UUID }
    }

    def "should read first found characteristic with matching UUID"() {
        given:
        def service = Mock BluetoothGattService
        shouldServiceContainCharacteristic(service, CHARACTERISTIC_UUID, CHARACTERISTIC_INSTANCE_ID, NOT_EMPTY_DATA)
        shouldServiceContainCharacteristic(service, OTHER_UUID, OTHER_INSTANCE_ID, OTHER_DATA)
        shouldDiscoverServices([service])
        shouldGattCallbackReturnDataOnRead(
                [uuid: OTHER_UUID, value: OTHER_DATA],
                [uuid: CHARACTERISTIC_UUID, value: NOT_EMPTY_DATA])

        when:
        objectUnderTest.readCharacteristic(CHARACTERISTIC_UUID).subscribe(testSubscriber)

        then:
        testSubscriber.assertValue NOT_EMPTY_DATA
    }

    def "should emit BleCharacteristicNotFoundException if there are no services during write operation"() {
        given:
        shouldDiscoverServices([])

        when:
        objectUnderTest.writeCharacteristic(CHARACTERISTIC_UUID, NOT_EMPTY_DATA).subscribe(testSubscriber)

        then:
        testSubscriber.assertError BleCharacteristicNotFoundException
        testSubscriber.assertError { it.charactersisticUUID == CHARACTERISTIC_UUID }
    }

    def "should emit BleCharacteristicNotFoundException if characteristic was not found during write operation"() {
        given:
        shouldGattContainServiceWithCharacteristic(null)

        when:
        objectUnderTest.writeCharacteristic(CHARACTERISTIC_UUID, NOT_EMPTY_DATA).subscribe(testSubscriber)

        then:
        testSubscriber.assertError BleCharacteristicNotFoundException
        testSubscriber.assertError { it.charactersisticUUID == CHARACTERISTIC_UUID }
    }

    @Unroll
    def "should write characteristic and return written value"() {
        given:
        def mockedCharacteristic = mockCharacteristicWithValue(uuid: CHARACTERISTIC_UUID, instanceId: CHARACTERISTIC_INSTANCE_ID, value: OTHER_DATA)
        shouldGattContainServiceWithCharacteristic(mockedCharacteristic, CHARACTERISTIC_UUID)
        def onWriteSubject = PublishSubject.create()
        gattCallback.getOnCharacteristicWrite() >> onWriteSubject

        when:
        setupWriteClosure.call(objectUnderTest, mockedCharacteristic, OTHER_DATA).subscribe(testSubscriber)

        then:
        testSubscriber.assertValue(OTHER_DATA)

        and:
        1 * bluetoothGattMock.writeCharacteristic({ it.getValue() == OTHER_DATA }) >> {
            BluetoothGattCharacteristic characteristic ->
                onWriteSubject.onNext(ByteAssociation.create(characteristic.getUuid(), characteristic.getValue()))
                true
        }

        where:
        setupWriteClosure << [
                writeCharacteristicUuidClosure,
                writeCharacteristicCharacteristicClosure
        ]
    }

    def "should emit retrieved rssi"() {
        given:
        shouldReturnStartingStatusAndEmitRssiValueThroughCallback {
            it.onNext(EXPECTED_RSSI_VALUE)
            true
        }

        when:
        objectUnderTest.readRssi().subscribe(testSubscriber)

        then:
        testSubscriber.assertValue(EXPECTED_RSSI_VALUE)
    }

    @Unroll
    def "should emit CharacteristicNotFoundException if matching characteristic wasn't found"() {
        given:
        shouldContainOneServiceWithoutCharacteristics()
        def characteristic = Mock(BluetoothGattCharacteristic)
        characteristic.getUuid() >> CHARACTERISTIC_UUID

        when:
        setupTriggerNotificationClosure.call(objectUnderTest, characteristic).flatMap({ it }).subscribe(testSubscriber)

        then:
        testSubscriber.assertError(BleCharacteristicNotFoundException)
        testSubscriber.assertError { it.charactersisticUUID == CHARACTERISTIC_UUID }

        where:
        setupTriggerNotificationClosure << [
                { RxBleConnection connection, BluetoothGattCharacteristic aCharacteristic -> return connection.setupNotification(aCharacteristic.getUuid()) },
                { RxBleConnection connection, BluetoothGattCharacteristic aCharacteristic -> return connection.setupIndication(aCharacteristic.getUuid()) }
        ]
    }

    @Unroll
    def "should call NotificationAndIndicationManager when called by .setupNotification() / .setupIndication() properly"() {

        given:
        def characteristic = Mock BluetoothGattCharacteristic
        shouldGattContainServiceWithCharacteristic(characteristic)

        when:
        setupClosure.call(objectUnderTest, characteristic, mode)

        then:
        1 * notificationAndIndicationManagerMock.setupServerInitiatedCharacteristicRead(characteristic, mode, ack) >> Observable.empty()

        where:
        mode                          | ack   | setupClosure
        NotificationSetupMode.DEFAULT | false | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupNotification(aChar) }
        NotificationSetupMode.DEFAULT | false | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupNotification(aChar.getUuid()).subscribe() }
        NotificationSetupMode.DEFAULT | false | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupNotification(aChar, nsm) }
        NotificationSetupMode.DEFAULT | false | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupNotification(aChar.getUuid(), nsm).subscribe() }
        NotificationSetupMode.DEFAULT | true  | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupIndication(aChar) }
        NotificationSetupMode.DEFAULT | true  | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupIndication(aChar.getUuid()).subscribe() }
        NotificationSetupMode.DEFAULT | true  | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupIndication(aChar, nsm) }
        NotificationSetupMode.DEFAULT | true  | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupIndication(aChar.getUuid(), nsm).subscribe() }
        NotificationSetupMode.COMPAT  | false | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupNotification(aChar, nsm) }
        NotificationSetupMode.COMPAT  | false | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupNotification(aChar.getUuid(), nsm).subscribe() }
        NotificationSetupMode.COMPAT  | true  | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupIndication(aChar, nsm) }
        NotificationSetupMode.COMPAT  | true  | { RxBleConnection con, BluetoothGattCharacteristic aChar, NotificationSetupMode nsm -> return con.setupIndication(aChar.getUuid(), nsm).subscribe() }
    }

    def "should pass items emitted by observable returned from RxBleRadioOperationCustom.asObservable()"() {
        given:
        def radioOperationCustom = customRadioOperationWithOutcome {
            just(true, false, true)
        }

        when:
        objectUnderTest.queue(radioOperationCustom).subscribe(testSubscriber)

        then:
        testSubscriber.assertCompleted()
        testSubscriber.assertValues(true, false, true)
    }

    def "should pass error and release the radio if custom operation will throw out of RxBleRadioOperationCustom.asObservable()"() {
        given:
        def radioOperationCustom = customRadioOperationWithOutcome { throw new RuntimeException() }

        when:
        objectUnderTest.queue(radioOperationCustom).subscribe(testSubscriber)

        then:
        flatRadio.semaphore.isReleased()
        testSubscriber.assertError(RuntimeException.class)
    }

    def "should pass error and release the radio if observable returned from RxBleRadioOperationCustom.asObservable() will emit error"() {
        given:
        def radioOperationCustom = customRadioOperationWithOutcome { Observable.error(new RuntimeException()) }

        when:
        objectUnderTest.queue(radioOperationCustom).subscribe(testSubscriber)

        then:
        flatRadio.semaphore.isReleased()
        testSubscriber.assertError(RuntimeException.class)
    }

    def "should release the radio when observable returned from RxBleRadioOperationCustom.asObservable() will complete"() {
        given:
        def radioOperationCustom = customRadioOperationWithOutcome { Observable.empty() }

        when:
        objectUnderTest.queue(radioOperationCustom).subscribe(testSubscriber)

        then:
        flatRadio.semaphore.isReleased()
        testSubscriber.assertCompleted()
    }

    def "should throw illegal argument exception if RxBleRadioOperationCustom.asObservable() return null"() {
        given:
        def radioOperationCustom = customRadioOperationWithOutcome { null }

        when:
        objectUnderTest.queue(radioOperationCustom).subscribe(testSubscriber)

        then:
        flatRadio.semaphore.isReleased()
        testSubscriber.assertError(IllegalArgumentException.class)
    }

    public customRadioOperationWithOutcome(Closure<Observable<Boolean>> outcomeSupplier) {
        new RxBleRadioOperationCustom<Boolean>() {

            @NonNull
            @Override
            Observable<Boolean> asObservable(BluetoothGatt bluetoothGatt,
                                             RxBleGattCallback rxBleGattCallback,
                                             Scheduler scheduler) throws Throwable {
                outcomeSupplier()
            }
        }
    }

    public mockDescriptorAndAttachToCharacteristic(BluetoothGattCharacteristic characteristic) {
        def descriptor = Spy(BluetoothGattDescriptor, constructorArgs: [RxBleConnectionImpl.CLIENT_CHARACTERISTIC_CONFIG_UUID, 0])
        descriptor.getCharacteristic() >> characteristic
        characteristic.getDescriptor(RxBleConnectionImpl.CLIENT_CHARACTERISTIC_CONFIG_UUID) >> descriptor
        descriptor
    }

    public shouldGattContainServiceWithCharacteristic(BluetoothGattCharacteristic characteristic, UUID characteristicUUID = CHARACTERISTIC_UUID) {
        characteristic.getUuid() >> characteristicUUID
        shouldContainOneServiceWithoutCharacteristics().getCharacteristic(characteristicUUID) >> characteristic
    }

    public shouldContainOneServiceWithoutCharacteristics() {
        def service = Mock BluetoothGattService
        shouldDiscoverServices([service])
        service
    }

    public shouldReturnStartingStatusAndEmitRssiValueThroughCallback(Closure<Boolean> closure) {
        def rssiSubject = PublishSubject.create()
        gattCallback.getOnRssiRead() >> rssiSubject
        bluetoothGattMock.readRemoteRssi() >> { closure?.call(rssiSubject) }
    }

    public shouldServiceContainCharacteristic(BluetoothGattService service, UUID uuid, int instanceId, byte[] characteristicValue) {
        service.getCharacteristic(uuid) >> mockCharacteristicWithValue(uuid: uuid, instanceId: instanceId, value: characteristicValue)
    }

    public shouldGattCallbackReturnDataOnRead(Map... parameters) {
        gattCallback.getOnCharacteristicRead() >> { from(parameters.collect { ByteAssociation.create it['uuid'], it['value'] }) }
    }

    public mockCharacteristicWithValue(Map characteristicData) {
        def characteristic = Mock BluetoothGattCharacteristic
        characteristic.getValue() >> characteristicData['value']
        characteristic.getUuid() >> characteristicData['uuid']
        characteristic.getInstanceId() >> characteristicData['instanceId']
        characteristic
    }

    public shouldDiscoverServices(ArrayList<BluetoothGattService> services) {
        mockServiceDiscoveryManager.getDiscoverServicesObservable(_, _) >> just(new RxBleDeviceServices(services))
    }

    public shouldFailStartingCharacteristicWrite() {
        bluetoothGattMock.writeCharacteristic(_) >> false
    }

    public shouldFailStartingCharacteristicRead() {
        bluetoothGattMock.readCharacteristic(_) >> false
    }

    private static Closure<Observable<byte[]>> readCharacteristicUuidClosure = { RxBleConnection connection, BluetoothGattCharacteristic characteristic -> return connection.readCharacteristic(characteristic.getUuid()) }

    private static Closure<Observable<byte[]>> readCharacteristicCharacteristicClosure = { RxBleConnection connection, BluetoothGattCharacteristic characteristic -> return connection.readCharacteristic(characteristic) }

    private static Closure<Observable<byte[]>> writeCharacteristicUuidClosure = { RxBleConnection connection, BluetoothGattCharacteristic characteristic, byte[] data -> return connection.writeCharacteristic(characteristic.getUuid(), data) }

    private static Closure<Observable<byte[]>> writeCharacteristicCharacteristicClosure = { RxBleConnection connection, BluetoothGattCharacteristic characteristic, byte[] data -> return connection.writeCharacteristic(characteristic, data) }

    private static Closure<Observable<byte[]>> writeCharacteristicCharacteristicDeprecatedClosure = { RxBleConnection connection, BluetoothGattCharacteristic characteristic, byte[] data ->
        characteristic.setValue(data)
        return connection.writeCharacteristic(characteristic)
    }

}
