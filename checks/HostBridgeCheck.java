package checks;

import android.content.Context;
import h.Hchat.host.FengniaoApis;
import java.lang.reflect.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** In-memory fixtures only. Does not load or execute the target APK. */
public class HostBridgeCheck {
    public static final class Fixture {
        public static String[][] arrays(int[] a, Object[][] b, boolean c, long d) { return null; }
        public void nothing() { }
        public String overloaded(String input) { return input; }
        public int overloaded(int input) { return input; }
    }
    static void require(boolean ok, String label) { if (!ok) throw new AssertionError(label); }
    static void rejected(FengniaoApis api, String descriptor) {
        try { api.resolve(descriptor); throw new AssertionError("accepted: " + descriptor); }
        catch (Throwable error) {
            if (!(error instanceof IllegalArgumentException) && !(error instanceof NoSuchMethodException))
                throw new AssertionError("unexpected rejection: " + descriptor, error);
        }
    }
    public static void main(String[] args) throws Exception {
        Constructor<FengniaoApis> ctor = FengniaoApis.class.getDeclaredConstructor(Context.class, ClassLoader.class);
        ctor.setAccessible(true);
        FengniaoApis api = ctor.newInstance(new android.content.ContextWrapper(null), HostBridgeCheck.class.getClassLoader());
        String prefix = "Lchecks/HostBridgeCheck$Fixture;->";
        Method arrays = api.resolve(prefix + "arrays([I[[Ljava/lang/Object;ZJ)[[Ljava/lang/String;");
        require(arrays.getParameterTypes()[0] == int[].class && arrays.getReturnType() == String[][].class, "arrays/primitives");
        require(api.resolve(prefix + "nothing()V").getReturnType() == void.class, "void");
        require(api.resolve(prefix + "overloaded(I)I").getParameterTypes()[0] == int.class, "overload-int");
        require(api.resolve(prefix + "overloaded(Ljava/lang/String;)Ljava/lang/String;").getParameterTypes()[0] == String.class, "overload-object");
        rejected(api, prefix + "nothing()I");
        rejected(api, prefix + "arrays([)V");
        rejected(api, "bad descriptor");
        System.out.println("descriptor resolution PASS");

        AtomicInteger success = new AtomicInteger(), failure = new AtomicInteger();
        Constructor<?> requestCtor = FengniaoApis.NativeRequest.class.getDeclaredConstructors()[0];
        requestCtor.setAccessible(true);
        Method finish = java.util.Arrays.stream(FengniaoApis.NativeRequest.class.getDeclaredMethods()).filter(m -> m.getName().startsWith("finish")).findFirst().orElseThrow();
        finish.setAccessible(true);
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 100; i++) {
                Object handle = requestCtor.newInstance((Consumer<Object>) value -> success.incrementAndGet(), (Consumer<Object>) value -> failure.incrementAndGet());
                CountDownLatch gate = new CountDownLatch(1);
                Future<?>[] futures = new Future<?>[4];
                for (int j = 0; j < 4; j++) { final boolean ok = j % 2 == 0; futures[j] = threads.submit(() -> { try { gate.await(); finish.invoke(handle, ok, "fixture"); } catch (Exception error) { throw new RuntimeException(error); } }); }
                gate.countDown(); for (Future<?> future : futures) future.get();
                require(success.get() + failure.get() == i + 1, "single-delivery");
            }
            Object closed = requestCtor.newInstance((Consumer<Object>) value -> success.incrementAndGet(), (Consumer<Object>) value -> failure.incrementAndGet());
            ((AutoCloseable) closed).close(); finish.invoke(closed, true, "late"); finish.invoke(closed, false, "late");
            require(success.get() + failure.get() == 100, "close-suppresses-delivery");
        } finally { threads.shutdownNow(); }
        System.out.println("native callback single-delivery/close PASS");
    }
}
