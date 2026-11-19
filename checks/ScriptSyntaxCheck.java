import java.nio.file.Files;
import java.nio.file.Path;
import bsh.ScriptSyntaxValidator;
public class ScriptSyntaxCheck {
    public static void main(String[] args) throws Exception {
        String runtime = Files.readString(Path.of(args[0], "app/src/main/java/h/Hchat/hooks/items/script/ScriptPluginRuntime.kt"));
        String quotes = Character.toString((char) 34).repeat(3);
        String marker = "private val PRELUDE = " + quotes;
        int start = runtime.indexOf(marker) + marker.length();
        String prelude = runtime.substring(start, runtime.indexOf(quotes + ".trimIndent", start));
        check("example-plugin", Files.readString(Path.of(args[0], "examples/api_catalog/main.java")));
        check("runtime-prelude", prelude);
        check("plugin-lifecycle", "void onLoad() { log(fn.catalog(\"setting\").toString()); } void onUnload() { log(\"关闭\"); } void openSettings() { toast(pluginName); }");
        check("async-http", "async(new Runnable() { public void run() { try { log(http.get(\"https://example.com\")); } catch(Exception error) { log(error); } } });");
        check("hook-callback", "Object handle; void onLoad() { Method m = firstMethod(findClass(\"example.ConfirmedClass\"), \"confirmedMethod\", 0); handle = hookAfter(m, new Consumer() { public void accept(Object param) { log(param.getResult()); } }); } void onUnload() { unhook(handle); }");
    }
    static void check(String name, String source) {
        ScriptSyntaxValidator.Result result = ScriptSyntaxValidator.validate(source);
        if (result.error != null) throw new AssertionError(name + ": " + result.error + " at " + result.line + ":" + result.column);
        System.out.println(name + " PASS");
    }
}
