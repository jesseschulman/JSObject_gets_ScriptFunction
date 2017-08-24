import jdk.nashorn.api.scripting.NashornScriptEngine;
import jdk.nashorn.api.scripting.NashornScriptEngineFactory;
import jdk.nashorn.api.scripting.ScriptObjectMirror;

import javax.script.Bindings;
import javax.script.ScriptException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

public class ScriptLoopReproducer {

    public static final String TEST = "describe('A test', function () {\n" +
            "  it('compares two strings', function () {\n" +
            "    expect('foo').toEqual('foo');\n" +
            "  });\n" +
            "});";
    private NashornScriptEngine engine;

    public static void main(String[] args) throws ScriptException, IOException {
        new ScriptLoopReproducer().reproduce();
    }

    private void reproduce() throws ScriptException, IOException {
        engine = (NashornScriptEngine) new NashornScriptEngineFactory().getScriptEngine(new String[] { "--no-java", "-strict",
                "--no-syntax-extensions", "--language=es6", "--optimistic-types=true" });

        for (int i = 0; i < 100; i++) {
            System.out.println("Doing loop: " + i);
            runJasmineTest();
        }
    }

    private ScriptObjectMirror require(File file, Bindings bindings) throws IOException {

        ScriptObjectMirror modulesObject = (ScriptObjectMirror) ((ScriptObjectMirror) bindings.get("Object")).newObject();

        ScriptObjectMirror modulesExportsObject = (ScriptObjectMirror) ((ScriptObjectMirror) bindings.get("Object")).newObject();
        modulesObject.put("exports", modulesExportsObject);

        String[] functionObjectArgs = new String[] {"module", "exports", fileToString(file)};

        Object[] functionCallArgs = new Object[] { modulesObject, modulesExportsObject };

        ScriptObjectMirror f = ((ScriptObjectMirror) ((ScriptObjectMirror) bindings.get("Function")).newObject((Object[]) functionObjectArgs));
        if (f == null)
            return null;

        f.call(f, functionCallArgs);

        Object exportsObject = modulesObject.get("exports");

        if (exportsObject instanceof ScriptObjectMirror)
            return (ScriptObjectMirror)exportsObject;

        return null;
    }

    private void runJasmineTest() throws IOException, ScriptException {
        Bindings bindings = engine.createBindings();
        bindings.put("global", bindings);

        ScriptEventLoop loop = new ScriptEventLoop(bindings);
        ScriptObjectMirror jasmineRequire = require(new File("jasmine.js"), bindings);

        ScriptObjectMirror jasmine = (ScriptObjectMirror) jasmineRequire.callMember("core", jasmineRequire);
        bindings.put("jasmine", jasmine);

        ScriptObjectMirror jasmineEnv = (ScriptObjectMirror) jasmine.callMember("getEnv");

        ScriptObjectMirror jasmineInterface = (ScriptObjectMirror) jasmineRequire.callMember("interface", jasmine, jasmineEnv);

        for (String global : jasmineInterface.keySet())
            bindings.put(global, jasmineInterface.get(global));

        jasmineEnv.callMember("addReporter", jasmineInterface.getMember("jsApiReporter"));

        jasmineEnv.callMember("execute");

        engine.eval(TEST, bindings);

        loop.runLoop();
        Object obj = ((ScriptObjectMirror) bindings.get("jsApiReporter")).callMember("specs");

    }

    private String fileToString(File f) throws IOException {
        if (!f.exists())
            throw new RuntimeException("File does not exist " + f);

        StringBuilder sb = new StringBuilder();
        for (String s : Files.readAllLines(f.toPath())) {
            sb.append(s).append("\n");
        }
        return sb.toString();
    }

}
