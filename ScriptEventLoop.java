import jdk.nashorn.api.scripting.AbstractJSObject;
import jdk.nashorn.api.scripting.ScriptObjectMirror;
import jdk.nashorn.internal.runtime.Undefined;

import javax.script.Bindings;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ScriptEventLoop {

    private static final Logger LOGGER = Logger.getLogger(ScriptEventLoop.class.getName());
    private final Bindings bindings;
    private int jobCount = 0;
    private ScheduledExecutorService executor;
    private Map<Integer, Object> gTasks = new ConcurrentHashMap<>();
    private List<TimeoutFunction> gQueuedFunctions = new ArrayList<>();
    private boolean gInLoop = false;

    public ScriptEventLoop(Bindings b) {
        bindings = b;
        loadIntoBindings();
    }

    private void loadIntoBindings() {
        bindings.put("setTimeout", new SetTimeoutFunction());
        bindings.put("setImmediate", new SetImmediateFunction());
        bindings.put("setInterval", new SetIntervalFunction());
        ClearFunction clear = new ClearFunction();
        bindings.put("clearTimeout", clear);
        bindings.put("clearImmediate", clear);
        bindings.put("clearInterval", clear);
    }

    public void runLoop() {
        gInLoop = true;
        try {
            for (TimeoutFunction function : gQueuedFunctions)
                giveToExecutor(function);

            gQueuedFunctions.clear();

            while (!gTasks.isEmpty()) {
                for (Iterator<Map.Entry<Integer, Object>> iter = gTasks.entrySet().iterator(); iter.hasNext(); ) {
                    Map.Entry<Integer, Object> entry = iter.next();

                    Object task = entry.getValue();
                    if (task instanceof ScheduledFuture && ((ScheduledFuture) task).isDone())
                        iter.remove();
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Exception during runLoop", e);
        }

        getExecutor().shutdown();

        if (!getExecutor().isShutdown())
            LOGGER.log(Level.SEVERE, "Finished running loop but executor did not shut down");

        executor = null;
        gInLoop = false;
    }

    private synchronized ScheduledExecutorService getExecutor() {
        if (executor == null)
            executor = Executors.newSingleThreadScheduledExecutor();

        return executor;
    }

    private void schedule(TimeoutFunction function) {
        if (gInLoop)
            giveToExecutor(function);
        else
            gQueuedFunctions.add(function);
    }

    private void giveToExecutor(TimeoutFunction function) {
        long startDelay = System.currentTimeMillis() - function.gShouldStart;
        if (function.gInterval == -1)
            gTasks.put(function.gTaskId, getExecutor().schedule(function, startDelay > 0 ? startDelay : 0, TimeUnit.MILLISECONDS));
        else
            gTasks.put(function.gTaskId, getExecutor().scheduleAtFixedRate(function, startDelay, function.gInterval, TimeUnit.MILLISECONDS));
    }

    private void cancel(Integer taskId) {
        Object task = gTasks.remove(taskId);
        if (task instanceof ScheduledFuture)
            ((ScheduledFuture) task).cancel(false);
    }

    private void checkArgs(Object ... args) {
        if (args.length < 2)
            throw new RuntimeException("Should get 2 args");

        if (!(args[0] instanceof ScriptObjectMirror))
            throw new RuntimeException("Did not get ScriptObjectMirror as first arg");

        if (!((ScriptObjectMirror) args[0]).isFunction())
            throw new RuntimeException("First arg was not a function");

        if (!((args[1] instanceof Integer)))
            throw new RuntimeException("Second arg was not an Integer");
    }

    private Object setTimeout(Object ... args) {
        checkArgs(args);
        int funcId = jobCount++;
        TimeoutFunction function = new TimeoutFunction(funcId, (ScriptObjectMirror) args[0], System.currentTimeMillis() + (Integer) args[1], Arrays.copyOfRange(args, 2, args.length));
        gTasks.put(funcId, function);
        schedule(function);
        return funcId;
    }

    private Object setInterval(Object ... args) {
        checkArgs(args);
        int funcId = jobCount++;
        Integer delay = (Integer) args[1];
        TimeoutFunction function = new TimeoutFunction(funcId, (ScriptObjectMirror) args[0], System.currentTimeMillis() + delay, Arrays.copyOfRange(args, 2, args.length));
        function.setInterval(delay);
        gTasks.put(funcId, function);
        schedule(function);
        return funcId;
    }

    class SetTimeoutFunction extends AbstractJSObject {

        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public Object call(Object thiz, Object ... args) {
            return setTimeout(args);
        }

    }

    class SetIntervalFunction extends AbstractJSObject {

        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public Object call(Object thiz, Object ... args) {
            return setInterval(args);
        }

    }

    class ClearFunction extends AbstractJSObject {

        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public Object call(Object thiz, Object ... args) {
            if (args.length < 1 || !(args[0] instanceof Integer))
                return Undefined.getUndefined();

            cancel((Integer) args[0]);
            return Undefined.getUndefined();
        }

    }

    class SetImmediateFunction extends AbstractJSObject {

        @Override
        public boolean isFunction() {
            return true;
        }

        @Override
        public Object call(Object thiz, Object ... args) {
            List<Object> newArgs = new ArrayList<>();
            if (args.length < 1)
                return Undefined.getUndefined();

            newArgs.add(args[0]);
            newArgs.add(0);
            for (int i = 1; i < args.length; i++)
                newArgs.add(args[i]);

            return setTimeout(newArgs.toArray());
        }

    }

    class TimeoutFunction implements Runnable {

        int gTaskId = -1;
        ScriptObjectMirror gFunction;
        Object[] gArgs;
        Long gShouldStart;
        Integer gInterval = -1;

        TimeoutFunction (int taskId, ScriptObjectMirror function, Long shouldStart, Object[] args) {
            gTaskId = taskId;
            gFunction = function;
            gArgs = args;
            gShouldStart = shouldStart;
        }

        public void setInterval(Integer interval) {
            gInterval = interval;
        }

        @Override
        public void run() {
            gFunction.call(gFunction, gArgs);
        }
    }

}
