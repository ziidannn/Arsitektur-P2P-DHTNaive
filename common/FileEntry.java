
package common;

public class FileEntry {
    public String filename;
    public int hash;

    public FileEntry(String filename) {
        this.filename = filename;
        this.hash = Math.abs(filename.hashCode()) % 32;
    }
}
