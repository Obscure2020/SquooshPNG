public class MinRuns {
    private final String title;
    private final long previousBest;
    private long newBest;
    private StringBuilder ordering;

    public MinRuns(String newTitle, long oldBest){
        previousBest = oldBest;
        newBest = oldBest;
        title = newTitle;
        ordering = new StringBuilder();
    }

    public String update(String runName, long value, boolean resetLine){
        ordering.append(runName);
        StringBuilder temp = new StringBuilder();
        if(resetLine) temp.append('\r');
        temp.append(title);
        temp.append(": ");
        temp.append(ordering);
        if(value < newBest) newBest = value;
        if(newBest < previousBest){
            temp.append(" - ");
            temp.append(previousBest - newBest);
            temp.append("...");
        }
        return temp.toString();
    }

    public String initialReport(){
        StringBuilder temp = new StringBuilder(title);
        temp.append(": ");
        return temp.toString();
    }

    public String finalReport(boolean resetLine){
        StringBuilder temp = new StringBuilder();
        if(resetLine) temp.append('\r');
        temp.append(title);
        temp.append(": ");
        temp.append(ordering);
        if(newBest < previousBest){
            temp.append(" - Removed ");
            temp.append(previousBest - newBest);
            temp.append(" bytes!");
        } else {
            temp.append('.');
        }
        return temp.toString();
    }

    public long finalBest(){
        return Long.min(previousBest, newBest);
    }

    public boolean victory(){
        return newBest < previousBest;
    }
}