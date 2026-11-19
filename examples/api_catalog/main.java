// 本示例只读取内嵌目录，不发出业务请求。
void onLoad() {
    async(new Runnable() {
        public void run() {
            log(fn.metadata().toString());
            log(fn.catalog("setting", 0, 10).toString());
            log(fn.mistCatalog("setting", 0, 10).toString());
        }
    });
}
void onUnload() { }
void openSettings() { toast("接口目录示例；结果请在插件日志查看。"); }
