package now.calypso.backend;

import java.util.Random;

import org.junit.jupiter.api.TestInfo;

import com.rpl.rama.RamaModule;
import com.rpl.rama.ops.RamaFunction0;
import com.rpl.rama.ops.RamaFunction1;
import com.rpl.rama.test.InProcessCluster;
import com.rpl.rama.test.LaunchConfig;

public class TestHelpers {

    public static void launchModule(InProcessCluster ipc, RamaModule module, TestInfo testInfo) {
        int numTasks = 1 << new Random().nextInt(3);
        int numThreads = new Random().nextInt(Math.min(2, numTasks)) + 1;
        // to exercise serialization
        if (numTasks > 1)
            numThreads = Math.max(numThreads, 2);
        System.out.printf(
                "Launching %s module in %s.%s with %d tasks and %d threads\n",
                module.getClass().getSimpleName(),
                testInfo.getTestClass().isPresent() ? testInfo.getTestClass().get().getSimpleName() : "ClassNotFound",
                testInfo.getTestMethod().isPresent() ? testInfo.getTestMethod().get().getName() : "methodNotFound",
                numTasks,
                numThreads);
        LaunchConfig config = new LaunchConfig(numTasks, numThreads);
        if (numThreads > 1)
            config.numWorkers(2);
        ipc.launchModule(module, config);
    }

    public static void attainCondition(RamaFunction0<Boolean> fn) {
        attainConditionPred(() -> null, (Object q) -> fn.invoke());
    }

    public static <T> void attainConditionPred(RamaFunction0<T> queryFn, RamaFunction1<T, Boolean> predFn) {
        long start = System.nanoTime();
        while (true) {
            T q = queryFn.invoke();
            if (predFn.invoke(q)) {
                break;
            } else if (System.nanoTime() - start >= 45000000000L) { // 45 seconds
                throw new RuntimeException("Failed to attain condition, last query: " + q);
            } else {
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

}
