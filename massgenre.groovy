@Grab(group='commons-io', module='commons-io', version='2.4')
@Grab(group='de.u-mass', module='lastfm-java', version='0.1.2')
@Grab(group='org.jaudiotagger', module='jaudiotagger', version='2.0.1')

import groovy.io.FileType
import static Constants.*
import static Utils.*
import static org.apache.commons.io.FilenameUtils.*
import org.jaudiotagger.audio.*

import org.jaudiotagger.tag.*
class Constants {
    static final EXTENSIONS = ['mp3','flac','mp4']
    static final MINIMUM_FILES_IN_ALBUM = 5
}

class MassGenre {

}

class Utils {

  def static isMusic(File file) {

    return EXTENSIONS.contains(getExtension(file.path));
  }

}



class ArtistTitleDetector {
  //API Key: 3a5b5f24d557b317e6a09d7ead6c00a4
  // Secret: is 4ae8ce9e102ce2f1d4b1d87d086cef02
  def getDataFromAlbumPath(String albumFolder) {
    println getName(albumFolder)
    def albumData = getName(albumFolder).split('-')
    return [artist:albumData[0].trim(), title:albumData[1].trim()]



  }
  def getDataFromAlbumPath2(String albumFolder) {
    def artists = []
    def albums = []
    def results = [:]
    new File(albumFolder).eachFile (FileType.FILES) { file ->
      if (isMusic(file)) {
        //Mp3File mp3file = new Mp3File(file.path);
        def f = AudioFileIO.read(file);
        Tag tag = f.getTag();
        artists << tag.getFirst(FieldKey.ARTIST);
        albums << tag.getFirst(FieldKey.ALBUM);

      }
    }
    if (artists.size() >1 || albums.size()>1) {
      return [artist:artists[0], title:albums[0], message:""]

    } else {
      return [artist:artists[0], title:albums[0], message:"too many albums or artists"]

    }


  }

}

class Scanner {

  def scanFolder(folderPath) {
    def albums = []
    def unknwon = []
    def dir = new File(folderPath)
    dir.eachFile (FileType.DIRECTORIES) { file ->
      if (isAlbum(file.path)) {
        albums << file.path
      } else {
        unknwon << file.path
      }
      println "it is album ${file.path} ${isAlbum(file.path)}"
    }
    return [albums:albums,unknown:unknwon]

  }

  def isAlbum(albumFolder) {
    def musicFiles = []
    def dir = new File(albumFolder)
    dir.eachFile (FileType.FILES) { file ->
      if (isMusic(file)) {
        musicFiles << getBaseName(file.path)
      }

    }
    return (musicFiles.size > MINIMUM_FILES_IN_ALBUM?true:false)

  }

}



static  main(args) {
  def data = []
  def scanner = new Scanner()
  def detector = new ArtistTitleDetector()
  def res = scanner.scanFolder('/Users/luciano/Music/test')
  res.albums.each() {
    def d = detector.getDataFromAlbumPath2(it)
    //println "artist: $d.artist / album: $d.title"
    data << d
  }
  println "found ${res.albums.size} albums"
  println "found ${res.unknown.size} unknown folders"
  println data


}