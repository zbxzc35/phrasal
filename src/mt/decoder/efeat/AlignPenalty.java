package mt.decoder.efeat;

import java.util.ArrayList;
import java.util.List;

import edu.stanford.nlp.util.Pair;

import mt.base.ConcreteTranslationOption;
import mt.base.FeatureValue;
import mt.base.Featurizable;
import mt.base.IString;
import mt.base.Sequence;
import mt.decoder.feat.IncrementalFeaturizer;


/**
 * Note: This should only be used for phrases extracted using one-to-one
 * alignments, i.e. using the Berkeley "intersection."
 * 
 * @author Spence Green
 *
 */
public class AlignPenalty implements IncrementalFeaturizer<IString, String> {

  public static String FEATURE_NAME = "AlignPenalty";
  public static String FEATURE_NAME2 = "AlignPenalty2";
  
  private static boolean useTwoFeatures;
  
  public AlignPenalty(String... args) {
    assert args.length != 1;

    useTwoFeatures = (Integer.parseInt(args[0]) == 2);
  }
  
  private Pair<Integer,Integer> internalFeaturize(Featurizable<IString, String> f) {
    final int tOptLen = f.translatedPhrase.size();

    int numAlignments = 0;
    int numNullAlignments = 0;
    if(f.option.abstractOption.alignment.hasAlignment()) {
      for(int i = 0; i < tOptLen; i++) {

        final int[] sIndices = f.option.abstractOption.alignment.e2f(i);
        if(sIndices == null) 
          numNullAlignments++;
        else if(sIndices.length != 1)
          throw new RuntimeException(String.format("Many-to-one alignment...Shouldn't happen with intersect heuristic (sIndices %d)",sIndices.length));
        else
          numAlignments++;
      }
    } else
      numNullAlignments += f.translatedPhrase.size();
    
    return new Pair<Integer,Integer>(numAlignments,numNullAlignments);
  }
  
  @Override
  public FeatureValue<String> featurize(Featurizable<IString, String> f) {
    if (useTwoFeatures) return null;
    
    Pair<Integer,Integer> featVals = internalFeaturize(f);

    return new FeatureValue<String>(FEATURE_NAME, -1 * (featVals.first() + featVals.second()));
  }

  @Override
  public List<FeatureValue<String>> listFeaturize(Featurizable<IString, String> f) { 
    if(!useTwoFeatures) return null;
    
    Pair<Integer,Integer> featVals = internalFeaturize(f);

    List<FeatureValue<String>> feats = new ArrayList<FeatureValue<String>>();
    feats.add(new FeatureValue<String>(FEATURE_NAME, -1 * featVals.first()));
    feats.add(new FeatureValue<String>(FEATURE_NAME2, -1 * featVals.second()));
    
    return feats; 
  }

  @Override
  public void initialize(List<ConcreteTranslationOption<IString>> options, Sequence<IString> foreign) {}
  @Override
  public void reset() {}
}