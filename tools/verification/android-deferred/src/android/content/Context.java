package android.content;
public class Context {public <T> T getSystemService(Class<T> cls){return cls.cast(new android.net.ConnectivityManager());}}
