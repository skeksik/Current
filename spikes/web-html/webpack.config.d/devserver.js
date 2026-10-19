// Every app URL serves index.html (History API routing), and scripts load from the root on deep URLs.
config.output = config.output || {};
config.output.publicPath = "/";
if (config.devServer) {
    config.devServer.historyApiFallback = true;
    config.devServer.port = 8081;
    config.devServer.open = false;
}
