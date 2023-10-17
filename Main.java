import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;

class Main{
    private static final String[] filters = {"0", "1", "2", "3", "4", "m", "e", "p", "b"};
    private static final String[] sixteenBitFormats = {"gray16be", "ya16be", "rgb48be", "rgba64be"};

    private static File zopfliPNG(File inputFile, MinRuns mr) throws Exception{
        System.out.print(mr.initialReport());
        System.out.flush();
        CompletableFuture<?>[] asyncs = new CompletableFuture<?>[filters.length];
        HashMap<Long, String> ids = new HashMap<>(filters.length,1.0f);
        File outputDir = inputFile.getParentFile();
        synchronized(ids){
            for(int i=0; i<filters.length; i++){
                ProcessBuilder builder = new ProcessBuilder("zopflipng", "--iterations=15", "--filters="+filters[i],
                    "-y", inputFile.getName(), filters[i]+".png");
                builder.directory(outputDir);
                builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process proc = builder.start();
                ids.put(proc.pid(), filters[i]);
                asyncs[i] = proc.onExit().thenAcceptAsync(p -> {
                    String id = null;
                    synchronized(ids){
                        id = ids.get(p.pid());
                    }
                    File result = new File(outputDir, id+".png");
                    long resultSize = result.exists() ? result.length() : Long.MAX_VALUE;
                    synchronized(mr){
                        System.out.print(mr.update(id, resultSize, true));
                        System.out.flush();
                    }
                });
            }
        }
        for(CompletableFuture<?> cf : asyncs) cf.get();
        //Determine Final Result
        File finalResult = inputFile;
        HashSet<Long> sizeSet = new HashSet<>();
        for(int i=filters.length-1; i>=0; i--){ //We favor things earlier in the list by overwriting later items.
            File f = new File(outputDir, filters[i]+".png");
            if(!f.exists()) continue;
            long len = f.length();
            sizeSet.add(len);
            if(len == mr.finalBest()) finalResult = f;
        }
        if(mr.victory()){
            String finalVerdict = "Best filter: " + finalResult.getName().split("[.]")[0];
            if(sizeSet.size() <= 1){
                finalVerdict = "All filters equally beneficial.";
            }
            if(finalResult.getCanonicalFile().equals(inputFile.getCanonicalFile())){ //If finalResult was never overwritten...
                finalVerdict = "No files output. Falling back to previous stage.";
            }
            System.out.println(mr.finalReport(true) + " " + finalVerdict);
        } else {
            System.out.println(mr.finalReport(true) + " None made a difference.");
        }
        return finalResult;
    }

    private static int processImage(File inputFile) throws Exception{
        long originalSize = inputFile.length(), bestSize = originalSize;
        int width = 0, height = 0;
        String originalPixFmt = "";
        {
            FFprobe initalInfo = new FFprobe(inputFile, "width", "height", "pix_fmt");
            int exitCode = initalInfo.exitCode();
            if(exitCode != 0){
                System.out.println("Got error when attempting to FFPROBE the file:");
                System.out.println("[" + inputFile + "]");
                return exitCode;
            }
            String[] results = initalInfo.results();
            width = Integer.parseInt(results[0]);
            height = Integer.parseInt(results[1]);
            originalPixFmt = results[2];
        }
        System.out.println("[" + inputFile + "] - " + width + "x" + height + " - " + originalPixFmt);
        File outputDir = new File("workDir").getCanonicalFile();
        outputDir.mkdir();

        //FFMPEG File Normalization
        System.out.print("FFMPEG: ");
        System.out.flush();
        File bestFile = new File(outputDir, "source.png");
        {
            ProcessBuilder builder;
            if(Arrays.asList(sixteenBitFormats).contains(originalPixFmt)){
                builder = new ProcessBuilder("ffmpeg", "-hide_banner", "-y", "-i", inputFile.getCanonicalPath(),
                    "-c", "png", "-update", "1", "source.png");
            } else {
                builder = new ProcessBuilder("ffmpeg", "-hide_banner", "-y", "-i", inputFile.getCanonicalPath(),
                    "-c", "png", "-pix_fmt", "rgba", "-update", "1", "source.png");
            }
            builder.directory(outputDir);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start().onExit().get();
            if(!bestFile.exists()){
                System.out.println("Error. Work directory empty.");
                outputDir.delete();
                return 1;
            }
            long ffmpegSize = bestFile.length();
            if(ffmpegSize < bestSize){
                long diff = bestSize - ffmpegSize;
                System.out.println("Done. Removed " + diff + " bytes!");
                bestSize = ffmpegSize;
            } else {
                System.out.println("Done.");
            }
        }

        //OxiPNG Initial Runs
        boolean oxiHelped = false;
        {
            MinRuns mr = new MinRuns("Oxi", bestSize);
            System.out.print(mr.initialReport());
            System.out.flush();
            // First pass: With NX
            ProcessBuilder builder = new ProcessBuilder("oxipng", "-o", "max", "-s", "--nx", "source.png");
            builder.directory(outputDir);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start().onExit().get();
            System.out.print(mr.update("1", bestFile.length(), true));
            System.out.flush();
            //Second pass: Without NX
            builder = new ProcessBuilder("oxipng", "-o", "max", "-s", "source.png");
            builder.directory(outputDir);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start().onExit().get();
            System.out.print(mr.update("2", bestFile.length(), true));
            System.out.flush();
            //Third pass: With A
            //TODO: Figure out how to QUICKLY AND ACCURATELY check when this pass isn't necessary and skip it.
            builder = new ProcessBuilder("oxipng", "-o", "max", "-s", "-a", "source.png");
            builder.directory(outputDir);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.start().onExit().get();
            mr.update("3", bestFile.length(), false);
            System.out.println(mr.finalReport(true));
            oxiHelped = mr.finalBest() < bestSize;
            bestSize = mr.finalBest();
        }

        //Zopflipng Initial Run (although extremely likely to be the only run)
        boolean zopfliHelped = false;
        {
            MinRuns mr = new MinRuns("ZopfliPNG", bestSize);
            File newBestFile = zopfliPNG(bestFile, mr);
            zopfliHelped = mr.finalBest() < bestSize;
            bestSize = mr.finalBest();
            File movedBestFile = new File(outputDir, "victor-" + newBestFile.getName());
            Files.move(newBestFile.toPath(), movedBestFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if(movedBestFile.exists()){
                bestFile = movedBestFile;
            } else {
                System.out.println("Huh, strange error. Couldn't rename file. Falling back to Oxi result.");
            }
        }
        for(File f : outputDir.listFiles()){
            if(!f.getCanonicalFile().equals(bestFile.getCanonicalFile())) f.delete();
        }

        //Further Re-Runs (if necessary)
        if(oxiHelped && zopfliHelped){
            while(true){
                // Oxi Re-Run
                {
                    MinRuns mr = new MinRuns("Oxi", bestSize);
                    System.out.print(mr.initialReport());
                    System.out.flush();
                    // First pass: With NX
                    ProcessBuilder builder = new ProcessBuilder("oxipng", "-o", "max", "--zc", "12", "--nx", bestFile.getName());
                    builder.directory(outputDir);
                    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    builder.start().onExit().get();
                    System.out.print(mr.update("1", bestFile.length(), true));
                    System.out.flush();
                    //Second pass: Without NX
                    builder = new ProcessBuilder("oxipng", "-o", "max", "--zc", "12", bestFile.getName());
                    builder.directory(outputDir);
                    builder.redirectError(ProcessBuilder.Redirect.DISCARD);
                    builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                    builder.start().onExit().get();
                    mr.update("2", bestFile.length(), true);
                    System.out.println(mr.finalReport(true));
                    boolean oxiHelpedAgain = false;
                    oxiHelpedAgain = mr.finalBest() < bestSize;
                    bestSize = mr.finalBest();
                    if(!oxiHelpedAgain) break;
                }
                //Zopfli Re-Run
                {
                    MinRuns mr = new MinRuns("ZopfliPNG", bestSize);
                    File newBestFile = zopfliPNG(bestFile, mr);
                    boolean zopfliHelpedAgain = mr.finalBest() < bestSize;
                    bestSize = mr.finalBest();
                    File movedBestFile = new File(outputDir, "again-" + newBestFile.getName());
                    Files.move(newBestFile.toPath(), movedBestFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    if(movedBestFile.exists()){
                        bestFile = movedBestFile;
                    } else {
                        System.out.println("Huh, strange error. Couldn't rename file. Falling back to most recent Oxi result.");
                    }
                    if(!zopfliHelpedAgain) break;
                }
            }
        }

        //Final Report
        if(bestSize < originalSize){ //Success!
            FFprobe finalInfo = new FFprobe(bestFile, "pix_fmt");
            int exitCode = finalInfo.exitCode();
            if(exitCode != 0){
                System.out.println("Well, that's odd. FFPROBE failed on the result. Hopefully it's still good?");
            } else {
                String finalPixFmt = finalInfo.results()[0];
                if(originalPixFmt.equals(finalPixFmt)){
                    System.out.println("Pixel format remains " + originalPixFmt + ".");
                } else {
                    System.out.println("Pixel format changed: " + originalPixFmt + " -> " + finalPixFmt + ".");
                }
            }
            double upper = bestSize * 100;
            double lower = originalSize;
            double percent = upper / lower;
            System.out.printf("File size improved: %d -> %d (%.2f%%).", originalSize, bestSize, percent);
            System.out.println();
            Files.move(bestFile.toPath(), inputFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("File replaced.");
        } else { //No success...
            System.out.println("File size unchanged: " + originalSize + ".");
            System.out.println("Original file remains untouched.");
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
            boolean lineBreak = false;
            while(!fileQueue.isEmpty()){
                File f = fileQueue.removeFirst().getCanonicalFile();
                if(!f.exists()){
                    if(lineBreak) System.out.println();
                    System.out.println("Hmmm, this file seems to no longer exist:");
                    System.out.println("[" + f + "]");
                    lineBreak = true;
                    continue;
                }
                if(f.isDirectory()){
                    if(f.getName().charAt(0) != '.'){
                        for(File subFile : f.listFiles()) fileQueue.addLast(subFile);
                    }
                } else {
                    if(lineBreak) System.out.println();
                    FFprobe codecCheck = new FFprobe(f, "codec_name", "codec_long_name");
                    if(codecCheck.exitCode() != 0){
                        System.out.println("Got error when attempting to FFPROBE the file:");
                        System.out.println("[" + f + "]");
                        lineBreak = true;
                        continue;
                    }
                    String codec = codecCheck.results()[0];
                    String codecLong = codecCheck.results()[1];
                    if(codec.equals("png")){
                        processImage(f);
                    } else {
                        System.out.println("FFPROBE reports that this file is not a PNG:");
                        System.out.println("[" + f + "]");
                        System.out.println("This seems to contain \"" + codecLong + "\" instead.");
                    }
                    lineBreak = true;
                }
            }
        } else {
            FFprobe codecCheck = new FFprobe(inputFile, "codec_name", "codec_long_name");
            if(codecCheck.exitCode() != 0){
                System.out.println("Got error when attempting to FFPROBE the file:");
                System.out.println("[" + inputFile + "]");
                System.exit(codecCheck.exitCode());
            }
            String codec = codecCheck.results()[0];
            String codecLong = codecCheck.results()[1];
            if(!codec.equals("png")){
                System.out.println("FFPROBE reports that this file is not a PNG:");
                System.out.println("[" + inputFile + "]");
                System.out.println("This seems to contain \"" + codecLong + "\" instead.");
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