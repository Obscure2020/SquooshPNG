import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

class Main{
    private static String[] filters = {"0", "1", "2", "3", "4", "m", "e", "p", "b"};

    private static int processImage(File inputFile) throws Exception{
        FFprober.fetch(inputFile, "width", "height", "pix_fmt");
        if(FFprober.exitCode() != 0){
            System.out.println("Got error when attempting to FFPROBE the file:");
            System.out.println("[" + inputFile + "]");
            return FFprober.exitCode();
        }
        int width = Integer.parseInt(FFprober.results()[0]);
        int height = Integer.parseInt(FFprober.results()[1]);
        String originalPixFmt = FFprober.results()[2];
        System.out.println("File: " + inputFile);
        System.out.println("Resolution: " + width + "x" + height);
        long originalSize = inputFile.length();
        File outputDir = new File("workDir").getCanonicalFile();
        outputDir.mkdir();
        System.out.print("ZopfliPNG: ");
        System.out.flush();
        {
            CompletableFuture<?>[] asyncs = new CompletableFuture<?>[filters.length];
            HashMap<Long, String> ids = new HashMap<>(filters.length,1.0f);
            for(int i=0; i<filters.length; i++){
                ProcessBuilder builder = new ProcessBuilder("zopflipng", "--iterations=15", "--filters="+filters[i],
                    "--lossy_transparent", "-y", inputFile.getCanonicalPath(), filters[i]+".png");
                builder.directory(outputDir);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process proc = builder.start();
                synchronized(ids){
                    ids.put(proc.pid(), filters[i]);
                }
                asyncs[i] = proc.onExit().thenAcceptAsync(p -> {
                    String id = null;
                    synchronized(ids){
                        id = ids.get(p.pid());
                    }
                    synchronized(System.out){
                        System.out.print(id);
                        System.out.flush();
                    }
                });
            }
            for(CompletableFuture<?> cf : asyncs) cf.get();
        }
        System.out.println(" - Done.");
        if(outputDir.listFiles().length == 0){
            System.out.println("Error. Work directory empty.");
            outputDir.delete();
            return 1;
        }
        File bestResult = null;
        {
            long[] sizes = Arrays.stream(outputDir.listFiles()).mapToLong(File::length).sorted().distinct().toArray();
            for(File f : outputDir.listFiles()){
                if(f.length() == sizes[0]){
                    bestResult = f.getCanonicalFile();
                    break;
                }
            }
            if(sizes.length == 1){
                System.out.println("None made a difference.");
            } else {
                System.out.println("Best result was " + bestResult.getName().split("[.]")[0] + ".");
            }
        }
        long preOxiSize = bestResult.length();
        {
            System.out.print("OxiPNG: 1... ");
            System.out.flush();
            ProcessBuilder builder = new ProcessBuilder("oxipng", "-o", "max", "--nx", bestResult.getName());
            builder.directory(outputDir);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start().waitFor();
            System.out.print("2... ");
            System.out.flush();
            builder = new ProcessBuilder("oxipng", "-o", "max", bestResult.getName());
            builder.directory(outputDir);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start().waitFor();
        }
        System.out.println("Done.");
        long postOxiSize = bestResult.length();
        if(preOxiSize > postOxiSize){
            long diff = preOxiSize - postOxiSize;
            if(diff == 1){
                System.out.println("Savings of 1 byte!");
            } else {
                System.out.println("Savings of " + diff + " bytes!");
            }
        } else{
            System.out.println("No change on Oxi step.");
        }
        FFprober.fetch(bestResult, "pix_fmt");
        if(FFprober.exitCode() != 0){
            System.out.println("Got error when attempting to FFPROBE the file:");
            System.out.println("[" + bestResult + "]");
            return FFprober.exitCode();
        }
        String afterPixFmt = FFprober.results()[0];
        if(originalPixFmt.equals(afterPixFmt)){
            System.out.println("Pixel format remains " + originalPixFmt + ".");
        } else {
            System.out.println("Pixel format changed: " + originalPixFmt + " -> " + afterPixFmt + ".");
        }
        if(postOxiSize > originalSize){
            System.out.println("File size worsened: " + originalSize + " -> " + postOxiSize + ".");
            System.out.println("Original file remains untouched.");
        } else if(postOxiSize == originalSize){
            System.out.println("File size unchanged: " + originalSize + ".");
            System.out.println("Original file remains untouched.");
        } else {
            double upper = postOxiSize * 100;
            double lower = originalSize;
            double percent = upper / lower;
            System.out.printf("File size improved: %d -> %d (%.2f%%).", originalSize, postOxiSize, percent);
            System.out.println();
            Files.move(bestResult.toPath(), inputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("File replaced.");
        }
        for(File f : outputDir.listFiles()) f.delete();
        outputDir.delete();
        return 0;
    }

    public static void main(String[] args) throws Exception{
        File inputFile = null;
        {
            StringBuilder sb = new StringBuilder();
            for(String s : args){
                sb.append(s);
                sb.append(' ');
            }
            inputFile = new File(sb.toString().trim()).getCanonicalFile();
        }
        if(!inputFile.exists()){
            System.out.println("Error. Could not find locate the file:");
            System.out.println("[" + inputFile + "]");
            System.exit(1);
        }
        if(inputFile.isDirectory()){
            ArrayDeque<File> fileQueue = new ArrayDeque<File>();
            for(File f : inputFile.listFiles()) fileQueue.addLast(f);
            while(!fileQueue.isEmpty()){
                File f = fileQueue.removeFirst().getCanonicalFile();
                if(!f.exists()){
                    System.out.println("Hmmm, this file seems to no longer exist:");
                    System.out.println("[" + f + "]");
                    System.out.println();
                    continue;
                }
                if(f.isDirectory()){
                    if(f.getName().charAt(0) != '.'){
                        for(File subFile : f.listFiles()) fileQueue.addLast(subFile);
                    }
                } else {
                    FFprober.fetch(f, "codec_name", "codec_long_name");
                    if(FFprober.exitCode() != 0){
                        System.out.println("Got error when attempting to FFPROBE the file:");
                        System.out.println("[" + f + "]");
                        System.out.println();
                        continue;
                    }
                    String codec = FFprober.results()[0];
                    String codecLong = FFprober.results()[1];
                    if(codec.equals("png")){
                        processImage(f);
                    } else {
                        System.out.println("FFPROBE reports that this file is not a PNG:");
                        System.out.println("[" + f + "]");
                        System.out.println("This seems to be \"" + codecLong + "\" instead.");
                    }
                    System.out.println();
                }
            }
        } else {
            FFprober.fetch(inputFile, "codec_name", "codec_long_name");
            if(FFprober.exitCode() != 0){
                System.out.println("Got error when attempting to FFPROBE the file:");
                System.out.println("[" + inputFile + "]");
                System.exit(FFprober.exitCode());
            }
            String codec = FFprober.results()[0];
            String codecLong = FFprober.results()[1];
            if(!codec.equals("png")){
                System.out.println("FFPROBE reports that this file is not a PNG:");
                System.out.println("[" + inputFile + "]");
                System.out.println("This seems to be \"" + codecLong + "\" instead.");
                System.exit(1);
            }
            int exitCode = processImage(inputFile);
            if(exitCode == 0){
                return;
            } else {
                System.exit(exitCode);
            }
        }
    }
}