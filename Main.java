import java.io.File;
import java.nio.file.*;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Scanner;

class Main{
    private static int processImage(File inputFile) throws Exception{
        int width = 0;
        int height = 0;
        String originalPixFmt = null;
        {
            ProcessBuilder builder = new ProcessBuilder("ffprobe", "-hide_banner", "-v", "error", "-show_entries",
                "stream=width,height,pix_fmt", "-of", "default=noprint_wrappers=1", inputFile.getName());
            builder.directory(inputFile.getParentFile());
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process ffprobe = builder.start();
            int exitCode =  ffprobe.waitFor();
            if(exitCode != 0){
                System.out.println("Got error when attempting to FFPROBE the file:");
                System.out.println("[" + inputFile + "]");
                return exitCode;
            }
            Scanner scan = new Scanner(ffprobe.getInputStream());
            width = Integer.parseInt(scan.nextLine().split("[=]")[1]);
            height = Integer.parseInt(scan.nextLine().split("[=]")[1]);
            originalPixFmt = scan.nextLine().split("[=]")[1];
            scan.close();
        }
        System.out.println("File: " + inputFile);
        System.out.println("Resolution: " + width + "x" + height);
        long originalSize = inputFile.length();
        File outputDir = new File("workDir").getCanonicalFile();
        outputDir.mkdir();
        String[] filters = {"0", "1", "2", "3", "4", "m", "e", "p", "b"};
        Process[] procs = new Process[filters.length];
        for(int i=0; i<filters.length; i++){
            ProcessBuilder builder = new ProcessBuilder("zopflipng", "--iterations=15", "--filters="+filters[i],
                "--lossy_transparent", "-y", inputFile.getCanonicalPath(), filters[i]+".png");
            builder.directory(outputDir);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            procs[i] = builder.start();
        }
        System.out.print("ZopfliPNG: ");
        System.out.flush();
        System.gc();
        for(int i=0; i<filters.length; i++){
            procs[i].waitFor();
            System.out.print(filters[i]);
            System.out.flush();
        }
        System.out.println(" - Done.");
        if(outputDir.listFiles().length == 0){
            System.out.println("Error. Work directory empty.");
            outputDir.delete();
            return 1;
        }
        File bestResult = null;
        {
            File[] results = outputDir.listFiles();
            File saved = results[0];
            for(File f : results){
                if(f.length() < saved.length()) saved = f;
            }
            bestResult = saved.getCanonicalFile();
            long count = Arrays.stream(results).mapToLong(f -> f.length()).distinct().count();
            if(count == 1){
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
        String afterPixFmt = null;
        {
            ProcessBuilder builder = new ProcessBuilder("ffprobe", "-hide_banner", "-v", "error", "-show_entries",
                "stream=pix_fmt", "-of", "default=noprint_wrappers=1", bestResult.getName());
            builder.directory(outputDir);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process ffprobe = builder.start();
            int exitCode =  ffprobe.waitFor();
            if(exitCode != 0){
                System.out.println("Got error when attempting to FFPROBE the file:");
                System.out.println("[" + bestResult + "]");
                return exitCode;
            }
            Scanner scan = new Scanner(ffprobe.getInputStream());
            afterPixFmt = scan.nextLine().split("[=]")[1];
            scan.close();
        }
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
                if(f.isDirectory()){
                    if(f.getName().charAt(0) != '.'){
                        for(File subFile : f.listFiles()) fileQueue.addLast(subFile);
                    }
                } else {
                    String codec = null;
                    String codecLong = null;
                    {
                        ProcessBuilder builder = new ProcessBuilder("ffprobe", "-hide_banner", "-v", "error", "-show_entries",
                        "stream=codec_name,codec_long_name", "-of", "default=noprint_wrappers=1", f.getName());
                        builder.directory(f.getParentFile());
                        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                        Process ffprobe = builder.start();
                        int exitCode =  ffprobe.waitFor();
                        if(exitCode != 0){
                            System.out.println("Got error when attempting to FFPROBE the file:");
                            System.out.println("[" + f + "]");
                            continue;
                        }
                        Scanner scan = new Scanner(ffprobe.getInputStream());
                        codec = scan.nextLine().split("[=]")[1];
                        codecLong = scan.nextLine().split("[=]")[1];
                        scan.close();
                    }
                    if(codec.equals("png")){
                        processImage(f);
                    } else {
                        System.out.println("FFPROBE reports that this file is not a PNG:");
                        System.out.println("[" + f + "]");
                        System.out.println("This seems to be \"" + codecLong + "\" instead.");
                    }
                }
                System.out.println();
            }
        } else {
            {
                ProcessBuilder builder = new ProcessBuilder("ffprobe", "-hide_banner", "-v", "error", "-show_entries",
                    "stream=codec_name,codec_long_name", "-of", "default=noprint_wrappers=1", inputFile.getName());
                builder.directory(inputFile.getParentFile());
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                Process ffprobe = builder.start();
                int exitCode =  ffprobe.waitFor();
                if(exitCode != 0){
                    System.out.println("Got error when attempting to FFPROBE the file:");
                    System.out.println("[" + inputFile + "]");
                    System.exit(exitCode);
                }
                Scanner scan = new Scanner(ffprobe.getInputStream());
                String codec = scan.nextLine().split("[=]")[1];
                String codecLong = scan.nextLine().split("[=]")[1];
                scan.close();
                if(!codec.equals("png")){
                    System.out.println("FFPROBE reports that this file is not a PNG:");
                    System.out.println("[" + inputFile + "]");
                    System.out.println("This seems to be \"" + codecLong + "\" instead.");
                    System.exit(1);
                }
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