@Grab(group='commons-io', module='commons-io', version='2.4')
@Grab(group='de.u-mass', module='lastfm-java', version='0.1.2')
@Grab(group='org.jaudiotagger', module='jaudiotagger', version='2.0.1')
@Grab(group='org.codehaus.groovy.modules.http-builder', module='http-builder', version='0.7.1')
@Grab(group='org.yaml', module='snakeyaml', version='1.14')

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
import groovy.util.logging.Log

class Constants {
    static final EXTENSIONS = ['mp3','flac','m4a']
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

  def hasTag() {
    return tags.size() != 0
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


  def getDataFromAlbumPath(String albumFolder) {
    HashSet artists = []
    HashSet albums = []
    def results = [:]
    HashSet album_artist = []

    new File(albumFolder).eachFileRecurse (FileType.FILES) { file ->
      if (isMusic(file)) {
        try {
          def f = AudioFileIO.read(file);
          Tag tag = f.getTag();
          artists << tag.getFirst(FieldKey.ARTIST)
          albums << tag.getFirst(FieldKey.ALBUM)
          if (tag.getFirst(FieldKey.ALBUM_ARTIST))
            album_artist << tag.getFirst(FieldKey.ALBUM_ARTIST)
        } catch (Exception e) {

          return [message:'file problem: ' + e.message]
        }
      }
    }
    if (artists.size() == 1 && albums.size()==1) {
      return [artist:artists[0], title:albums[0], error:'']
    } else {
      // try with album artist
      if (album_artist.size()==1 && albums.size()==1) {
        return [artist:album_artist[0], title:albums[0], error:'']
      } else {
          return [artist:artists[0], title:albums[0], error:'']
      }
    }


  }

}

@Log
class LastFM {
  static final lastapi = new RESTClient( 'http://ws.audioscrobbler.com/2.0/' )

  def getTags(_artist, _album) {
    def resp = lastapi.get(query: [method: LAST_FM_METHOD,
                                   api_key:'3a5b5f24d557b317e6a09d7ead6c00a4',
                                   album:_album,
                                   artist:_artist, format:'json'])
    log.fine ("fetching tags for [$_artist] [$_album]")
    def td = new TagData()

    try {
      resp.data.album.toptags.tag.each() { tag ->
        td.add(tag.name, getTagPopularity(tag.name))
      }
    } catch (Exception e) {
      // fix this, ignore the exception and retun
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

@Log
class Scanner {

  def hasFolders(String  path) {

    def f = new File((String)path)
    int i = 0
    f.eachDir() {
      i++
    }
    return i>0
  }

  def scanRootFolder(folderPath) {
      def albums = new HashSet()
      def unknwon = new HashSet()
      def dir = new File(folderPath)
      dir.eachFileRecurse (FileType.DIRECTORIES) { file ->
        //println file.path

        if (isAlbum(file.path) ) {
          log.fine "scanning $file.path as album"
          if (isPartOfAlbum(file.path)) {
            albums << file.parent
            log.fine ("$file.path is added as subdir of album")
          } else {
            albums << file.path
          }

        } else {
          if (!albums.contains(file.parent) && !hasFolders(file.path)) {
            unknwon << file.path
            log.fine ("$file.path is added as unprocessable album")
          }
        }
      }
      return [albums:albums, unknwon:unknwon]
  }

  def isPartOfAlbum(folderPath) {
    // matches: cd1, c d 1, Disc1, disc 1
    def pattern = /(CD|cd|c d|(D|d)isc)( ?|-?)\d?\d/
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
    log.fine("scanned $albumFolder...found $musicFiles.size files")

    return (musicFiles.size >= MINIMUM_FILES_IN_ALBUM?true:false)

  }

}

@Log
class MassGenre {

  def prettyPrintList = { l ->
    def s = '\n'
    l.each() {
      s+='\t - '+it+'\n'

    }
    return s

  }

  def tagAlbum(String path, boolean dryrun = false) {

    def data = []
    def scanner = new Scanner()
    def detector = new ArtistTitleDetector()
    def last = new LastFM()
    def gt = new GenreTree()
    def wl = new WhiteList()
    def res = scanner.scanRootFolder(path)
    log.fine "scan result - found albums: ${prettyPrintList(res.albums)}"
    log.fine "scan result - unknwon albums: ${prettyPrintList(res.unknwon)}"

    res.albums.each() {
      def tag
      def tags
      def reason
      Map d = detector.getDataFromAlbumPath(it)
      if (!d.error) {
        tags = last.getTags(d.artist, d.title).getAll()
        // first use the genre tree
        def firstLastFmTag = ''
        for(def lastfmtag: tags) {
          if (!firstLastFmTag) firstLastFmTag = lastfmtag.key
          println ("processin tag [$lastfmtag.key]")
          def gtFound = gt.exists(lastfmtag.key)
          if (gtFound) {
            log.fine "[$lastfmtag.key] is in genre tree"
            tag = gtFound.genreRoot
            if (firstLastFmTag != lastfmtag.key) {
              log.info "Tag [$lastfmtag.key] found in genre tree, but most popular tag in lastfm [$firstLastFmTag] was not found"
            }
            log.info "tag for album [$d.artist / $d.title] is: [$tag]"

            break
          }

        }
      } else {
        log.error(d.error)
        reason = 'an error occurred while getting data from Album folder'
      }
      if (!tag) {
        println 'checking whitelist'
        for(def lastfmtag: tags) {
          tag = wl.contains(lastfmtag.key)
          if (tag) {
            log.fine "found tag $tag in whitelist"
            break;
          }
        }
      }
      if (!tag) {
        log.info "can't find tag for album: ${d.title}"
      }
     }
  }

}

static  main(args) {

  new MassGenre().tagAlbum(args[0])
}
