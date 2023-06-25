import java.io.*;
import java.util.Scanner;

public class FFprober {
    private static int exitCode = Integer.MIN_VALUE;
    private static String[] results = null;

    public static void fetch(File target, String ... values) throws Exception{
        StringBuilder sb = new StringBuilder("stream=");
        for(int i=0; i<values.length; i++){
            sb.append(values[i]);
            if(i < values.length-1) sb.append(',');
        }
        ProcessBuilder builder = new ProcessBuilder("ffprobe", "-hide_banner", "-v", "error", "-show_entries",
            sb.toString(), "-of", "default=noprint_wrappers=1", target.getName());
        builder.directory(target.getParentFile());
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process ffprobe = builder.start();
        exitCode = ffprobe.waitFor();
        if(exitCode != 0){
            results = new String[0];
            return;
        }
        InputStream is = ffprobe.getInputStream();
        String output = new String(is.readAllBytes());
        is.close();
        Scanner scan = new Scanner(output);
        results = new String[values.length];
        for(int i=0; i<values.length; i++) results[i] = scan.nextLine().split("[=]")[1];
        scan.close();
    }

    public static String[] results(){
        return results;
    }

    public static int exitCode(){
        return exitCode;
    }
}