package edu.stanford.nlp.mt.base;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.sleepycat.bind.tuple.IntegerBinding;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;

import edu.stanford.nlp.mt.decoder.feat.IsolatedPhraseFeaturizer;
import edu.stanford.nlp.mt.decoder.util.Scorer;

public class BinaryPhraseTable<FV> extends AbstractPhraseGenerator<IString, FV> {
  private String name;
  final int longestForeignPhrase;
  final String[] scoreNames;
  final Environment dbEnv;
  final Database db;
  
  public BinaryPhraseTable(IsolatedPhraseFeaturizer<IString, FV> phraseFeaturizer,
      Scorer<FV> scorer, String filename) throws IOException {
    super(phraseFeaturizer, scorer);
    name = String.format("BinaryPhraseTable(%s)", filename);
    EnvironmentConfig envConfig = new EnvironmentConfig();
    envConfig.setTransactional(false);
    envConfig.setAllowCreate(false);
    dbEnv = new Environment(new File(filename), envConfig);
    
    DatabaseConfig dbConfig = new DatabaseConfig();
    dbConfig.setTransactional(false);
    dbConfig.setSortedDuplicates(true);
    dbConfig.setAllowCreate(false);
    dbConfig.setReadOnly(true);
    
    db = dbEnv.openDatabase(null, "phrases", dbConfig);
    Database meta = dbEnv.openDatabase(null, "meta", dbConfig);
    DatabaseEntry metaKey = new DatabaseEntry("longestForeignPhrase".getBytes());
    DatabaseEntry metaValue = new DatabaseEntry();
    meta.get(null, metaKey, metaValue, LockMode.DEFAULT);
    System.err.printf("metaValue: "+metaValue);
    longestForeignPhrase = IntegerBinding.entryToInt(metaValue);
    metaKey = new DatabaseEntry("scoreNames".getBytes());
    meta.get(null, metaKey, metaValue, LockMode.DEFAULT);
    ByteArrayInputStream mbistrm = new ByteArrayInputStream(metaValue.getData());
    DataInputStream mdistrm = new DataInputStream(mbistrm);
    int scoreNamesLength = mdistrm.readInt();
    
    scoreNames = new String[scoreNamesLength];
    for (int i = 0; i < scoreNames.length; i++) {
      scoreNames[i] = mdistrm.readUTF(); 
    }
    meta.close();  
  }

  @Override
  public void setCurrentSequence(Sequence<IString> foreign, List<Sequence<IString>> tranList) {
     // no op - TODO make it so we don't need this stub    
  }
  
  static public void convertToBinaryPhraseTable(String flatPhraseTableName, String binaryPhraseTableName) throws IOException {
     File bf = new File(binaryPhraseTableName);
     if (bf.exists()) {
        bf.delete();
     }
     
     bf.mkdir();

     EnvironmentConfig envConfig = new EnvironmentConfig();
    envConfig.setTransactional(false);
    envConfig.setAllowCreate(true);
    Environment dbEnv = new Environment(new File(binaryPhraseTableName), envConfig);
    
    DatabaseConfig dbConfig = new DatabaseConfig();
    dbConfig.setTransactional(false);
    dbConfig.setSortedDuplicates(true);
    dbConfig.setAllowCreate(true);
    dbConfig.setDeferredWrite(true);
    FlatPhraseTable<String> flatPhraseTable = new FlatPhraseTable<String>(null, null, flatPhraseTableName); 
    
    Database db = dbEnv.openDatabase(null, "phrases", dbConfig);
    Database meta = dbEnv.openDatabase(null, "meta", dbConfig);
    DatabaseEntry metaKey = new DatabaseEntry("longestForeignPhrase".getBytes());
    DatabaseEntry metaValue = new DatabaseEntry();
    IntegerBinding.intToEntry(flatPhraseTable.longestForeignPhrase(), metaValue);
    meta.put(null, metaKey, metaValue);
    System.err.println("metavalue: "+metaValue);
    
    metaKey = new DatabaseEntry("scoreNames".getBytes());    
    ByteArrayOutputStream mbostrm = new ByteArrayOutputStream();
    DataOutputStream mdostrm = new DataOutputStream(mbostrm);
    mdostrm.writeInt(flatPhraseTable.scoreNames.length);    
    for (int i = 0; i < flatPhraseTable.scoreNames.length; i++) {
      mdostrm.writeUTF(flatPhraseTable.scoreNames[i]);      
    }
    mdostrm.flush();
    metaValue = new DatabaseEntry(mbostrm.toByteArray());
    meta.put(null, metaKey, metaValue);
    
    if (!(FlatPhraseTable.foreignIndex instanceof DynamicIntegerArrayIndex)) {
      throw new RuntimeException("Gap phrase-tables are currently not supported");
    }    
    DynamicIntegerArrayIndex diai = (DynamicIntegerArrayIndex)FlatPhraseTable.foreignIndex;
    
    for (int[] sourceInts : diai) {
      Sequence<IString> source = new RawSequence<IString>(sourceInts, IString.identityIndex());
      List<TranslationOption<IString>> translationOpts = flatPhraseTable.getTranslationOptions(source);
      DatabaseEntry key = new DatabaseEntry(source.toString().getBytes());
      // Todo - add support for alignments
      ByteArrayOutputStream bostrm = new ByteArrayOutputStream();
      DataOutputStream dostrm = new DataOutputStream(bostrm);
      dostrm.writeInt(translationOpts.size());
      for (TranslationOption<IString> opt : translationOpts) {       
        dostrm.writeUTF(opt.translation.toString());
        dostrm.write(opt.scores.length);
        for (int i = 0; i < opt.scores.length; i++) {
          dostrm.writeFloat(opt.scores[i]);
        }
        dostrm.flush();        
      }
      db.put(null, key, new DatabaseEntry(bostrm.toByteArray()));
    }
    meta.close();
    db.close();
    dbEnv.close();
  }

  @Override
  public String getName() {
    return name;
  }

  
  @Override
  public List<TranslationOption<IString>> getTranslationOptions(Sequence<IString> foreign) {
    DatabaseEntry key = new DatabaseEntry(foreign.toString().getBytes());
    DatabaseEntry value = new DatabaseEntry();
    if (db.get(null, key, value, LockMode.DEFAULT) != OperationStatus.SUCCESS) {
      return null;
    }
    ByteArrayInputStream bistrm = new ByteArrayInputStream(value.getData());
    DataInputStream distrm = new DataInputStream(bistrm);
    List<TranslationOption<IString>> translationOpts = null;
    RawSequence<IString> foreignRaw;
    if (foreign instanceof RawSequence) {
      foreignRaw = (RawSequence<IString>) foreign;
    } else {
      foreignRaw = new RawSequence<IString>(foreign);
    }
    try {
      int cnt = distrm.readInt();
      translationOpts = new ArrayList<TranslationOption<IString>>(cnt);
      float[] scores = null;
      for (int i = 0; i < cnt; i++) {
        String targetStr = distrm.readUTF();
        int scoresLength = distrm.read();
        scores = new float[scoresLength];
        for (int j = 0; j < scores.length; j++) {
          scores[j] = distrm.readFloat();          
        }
        RawSequence<IString> target = new RawSequence<IString>(IStrings.toIStringArray(targetStr.split("\\s")));
        translationOpts.add(new TranslationOption<IString>(scores, scoreNames, target, foreignRaw, null));
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return translationOpts;
  }

  @Override
  public int longestForeignPhrase() {
    return longestForeignPhrase;
  }
  public static void main(String[] args) throws IOException {
    if (args.length != 2) {
      System.err.println("Usage:\n\tjava ..BinaryPhraseTable (flat phrase-table) (binary phrase-table)");      
      System.err.println("\nDescription: Converts a flat phrase-table to a binary phrase-table");
      System.exit(-1);
    }
    convertToBinaryPhraseTable(args[0], args[1]);    
  }
}