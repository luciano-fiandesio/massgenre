@Grab(group='commons-io', module='commons-io', version='2.4')
@Grab(group='de.u-mass', module='lastfm-java', version='0.1.2')
@Grab(group='org.jaudiotagger', module='jaudiotagger', version='2.0.1')
@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1')
@Grab(group='org.yaml', module='snakeyaml', version='1.14')
@Grab(group='ch.qos.logback', module='logback-classic', version='1.0.13')


import groovy.io.FileType
import static Constants.*
import static Utils.*
import static org.apache.commons.io.FilenameUtils.*
import org.jaudiotagger.audio.*
import org.jaudiotagger.tag.*
import groovyx.net.http.RESTClient
import static groovyx.net.http.ContentType.*
import org.yaml.snakeyaml.*
import groovy.util.logging.Slf4j

class Constants {
    static final EXTENSIONS = ['mp3','flac','mp4']
    static final MINIMUM_FILES_IN_ALBUM = 5
    static final LAST_FM_METHOD = 'album.getinfo'
    static final DEFAULT_WHITELIST_LOCATION = 'genres/genres.txt'
    static final DEFAULT_GENRETREE_LOCATION = 'genres/genres-tree.yaml'
}


class GenreTreeData {

  public String genre
  public String genreParent
  public String genreRoot
  public GenreTreeData(g, gp, gr) {
    genre =genre
    genreParent = gp
    genreRoot = gr
  }
}

class TagData {
  def tags = [:]
  def getAll() {
    tags.sort { a, b -> new Long(b.value) <=> new Long(a.value) }
  }

  def getMostPopular() {
    tags.max{ new Long(it.value) }.key
  }

  def add(tag, popularity) {
    tags.put(tag, new Long(popularity))
  }

}

class GenreTree {
  def genres
  def genreMap = [:]

  def GenreTree(String path = DEFAULT_GENRETREE_LOCATION) {
    def yaml = new Yaml();
    genres = yaml.load(new FileInputStream(new File(path)))
    println "yaml has $genres.size root nodes"
    genres.each() {
      //println it.keySet()[0]
      genreMap.put(it.keySet()[0], new GenreTreeData(it.keySet()[0],it.keySet()[0],it.keySet()[0]))
      recurseGenres(it, it.keySet()[0])
    }
  }

  def recurseGenres(Map m, String root) {
    //println "entering: " + m.keySet()[0]
    m.values().each() {
        it.each() { sub ->
          if (sub instanceof Map) {
            //println sub.getClass().getName()
            recurseGenres(sub, root)
          } else {
            genreMap.put(sub, new GenreTreeData(sub,m.keySet()[0],root))
            //println sub.getClass().getName()  + "  " +sub
          }
        }
      }

  }


  def exists(String genre) {
    return genreMap[genre]
  }
  def String toString() {
    def r
    genreMap.each() {
      r+=it.key + " " + it.value + "\n"

    }
    return r
  }

}

class WhiteList {
  def genres = []

  def WhiteList(String path = DEFAULT_WHITELIST_LOCATION) {

    new File( path ).eachLine { line ->
      if (line.trim())
        genres << line
    }
    println "whitelist loaded: $genres.size"
  }

  def contains(String genre) {
    return genres.contains(genre)
  }



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
    new File(albumFolder).eachFileRecurse (FileType.FILES) { file ->
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

class LastFM {
  static final lastapi = new RESTClient( 'http://ws.audioscrobbler.com/2.0/' )

  def getTags(_artist, _album) {
    def resp = lastapi.get(query: [method: LAST_FM_METHOD,
                                   api_key:'3a5b5f24d557b317e6a09d7ead6c00a4',
                                   album:_album,
                                   artist:_artist, format:'json'])
    def td = new TagData()
    resp.data.album.toptags.tag.each() { tag ->
      //println tag.name
      td.add(tag.name, getTagPopularity(tag.name))

    }
    return td
  }

  def getTagPopularity(_tag) {
    def resp = lastapi.get(query: [method: 'tag.getinfo',
                                   api_key:'3a5b5f24d557b317e6a09d7ead6c00a4',
                                   tag:_tag,
                                  format:'json'])
    //println resp.data.tag.taggings
    return resp.data.tag.taggings

  }

}

class Scanner {

  def scanRootFolder(folderPath) {
      def albums = new HashSet()
      def unknwon = new HashSet()
      def dir = new File(folderPath)
      dir.eachFileRecurse (FileType.DIRECTORIES) { file ->
        //println file.path
        if (isAlbum(file.path) ) {
          if (isPartOfAlbum(file.path)) {
            albums << file.parent
          } else {
            albums << file.path
          }

        } else {
          unknwon << file.path
        }
      }
      return [albums:albums, unknwon:unknwon]
  }

  def isPartOfAlbum(folderPath) {
    // matches: cd1, c d 1, Disc1, disc 1
    def pattern = /(cd|c d|(D|d)isc)( ?|-?)\d?\d/
    return getBaseName(folderPath)==~pattern
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

@Slf4j
class MassGenre {

  def tagAlbum(String path, boolean dryrun = false) {

    def data = []
    def scanner = new Scanner()
    def detector = new ArtistTitleDetector()
    def last = new LastFM()
    def gt = new GenreTree()
    def wl = new WhiteList()
    def res = scanner.scanRootFolder(path)
    res.albums.each() {
      def tag
      def d = detector.getDataFromAlbumPath2(it)
      log.debug d.title

      def tags = last.getTags(d.artist, d.title).getAll()
      print tags
      for(def lastfmtag: tags) {
      //tags.each() { lastfmtag ->

        def gtFound = gt.exists(lastfmtag.key)
        log.debug "check $lastfmtag.key is in genre tree"
        if (gtFound) {
          log.debug "$lastfmtag.key is in genre tree!"
          tag = gtFound.genreRoot
          break
        }

      }
      if (!tag) {
        tag = 'not found'
      }
     }
  }

}

static  main(args) {

  new MassGenre().dox(args[0])
}
