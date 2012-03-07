package org.esa.beam.coastcolour.fuzzy;

import org.esa.beam.framework.datamodel.Band;
import org.esa.beam.framework.datamodel.ColorPaletteDef;
import org.esa.beam.framework.datamodel.ImageInfo;
import org.esa.beam.framework.datamodel.IndexCoding;
import org.esa.beam.framework.datamodel.Product;
import org.esa.beam.framework.datamodel.ProductData;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.annotations.OperatorMetadata;
import org.esa.beam.framework.gpf.annotations.Parameter;
import org.esa.beam.framework.gpf.annotations.SourceProduct;
import org.esa.beam.framework.gpf.pointop.PixelOperator;
import org.esa.beam.framework.gpf.pointop.ProductConfigurer;
import org.esa.beam.framework.gpf.pointop.Sample;
import org.esa.beam.framework.gpf.pointop.SampleConfigurer;
import org.esa.beam.framework.gpf.pointop.WritableSample;

import java.awt.Color;
import java.net.URL;

@SuppressWarnings({"UnusedDeclaration"})
@OperatorMetadata(alias = "CoastColour.FuzzyClassification",
                  description = ".",
                  authors = "Timothy Moore (University of New Hampshire); Marco Peters, Thomas Storm (Brockmann Consult)",
                  copyright = "(c) 2010 by Brockmann Consult",
                  version = "1.2")
public class FuzzyOp extends PixelOperator {

    private static final String AUXDATA_PATH = "owt16_meris_stats_101119_5band.hdf";
    private static final float[] BAND_WAVELENGTHS = new float[]{410.0f, 443.0f, 490.0f, 510.0f, 555.0f, 670.0f};

    private static final Color[] CLASS_COLORS = new Color[]{
            new Color(0.3438f, 0.0039f, 0.5703f),
            new Color(0.0039f, 0.1562f, 0.8008f),
            new Color(0.0039f, 1.0000f, 1.0000f),
            new Color(0.0469f, 0.9258f, 0.0039f),
            new Color(0.0039f, 0.4375f, 0.3281f),
            new Color(0.7490f, 0.5059f, 0.1765f),
            new Color(0.5490f, 0.3176f, 0.0392f),
            new Color(1.0000f, 0.0039f, 0.0039f),
            new Color(1.0000f, 1.0000f, 0f)
    };
    private static final int CLASS_COUNT = CLASS_COLORS.length;


    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @Parameter(defaultValue = "reflec")
    private String reflectancesPrefix;

    private FuzzyClassification fuzzyClassification;
    private int bandCount;

    @Override
    protected void configureTargetProduct(ProductConfigurer productConfigurer) {
        super.configureTargetProduct(productConfigurer);

        Product targetProduct = productConfigurer.getTargetProduct();
        for (int i = 1; i <= CLASS_COUNT; i++) {
            final Band classBand = targetProduct.addBand("class_" + i, ProductData.TYPE_FLOAT32);
            classBand.setValidPixelExpression(classBand.getName() + " > 0.0");
            classBand.setNoDataValueUsed(true);
        }
        final Band domClassBand = targetProduct.addBand("dominant_class", ProductData.TYPE_INT8);
        domClassBand.setNoDataValue(-1);
        domClassBand.setNoDataValueUsed(true);
        final IndexCoding indexCoding = new IndexCoding("Cluster_classes");
        final ColorPaletteDef.Point[] points = new ColorPaletteDef.Point[CLASS_COUNT];
        for (int i = 1; i <= CLASS_COUNT; i++) {
            String name = "class_" + i;
            indexCoding.addIndex(name, i, "Class " + i);
            points[i - 1] = new ColorPaletteDef.Point(i - 1, CLASS_COLORS[i - 1], name);
        }
        targetProduct.getIndexCodingGroup().add(indexCoding);
        domClassBand.setSampleCoding(indexCoding);
        domClassBand.setImageInfo(new ImageInfo(new ColorPaletteDef(points, points.length)));


        final Band sumBand = targetProduct.addBand("class_sum", ProductData.TYPE_FLOAT32);
        sumBand.setValidPixelExpression(sumBand.getName() + " > 0.0");
    }

    @Override
    protected void configureSourceSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        final URL resourceUrl = FuzzyClassification.class.getResource(AUXDATA_PATH);
        final Auxdata auxdata;
        try {
            auxdata = new Auxdata(resourceUrl.toURI());
        } catch (Exception e) {
            throw new OperatorException("Unable to load auxdata", e);
        }

        fuzzyClassification = new FuzzyClassification(auxdata.getSpectralMeans(),
                                                      auxdata.getInvertedCovarianceMatrices());
        bandCount = auxdata.getSpectralMeans().length;
        for (int i = 0; i < bandCount; i++) {
            final String bandName = getSourceBandName(reflectancesPrefix, BAND_WAVELENGTHS[i]);
            sampleConfigurer.defineSample(i, bandName);
        }
    }

    private String getSourceBandName(String reflectancesPrefix, float wavelength) {
        final double maxDistance = 10.0;
        final String[] bandNames = sourceProduct.getBandNames();
        String bestBandName = null;
        double wavelengthDist = Double.MAX_VALUE;
        for (String bandName : bandNames) {
            final Band band = sourceProduct.getBand(bandName);
            final boolean isSpectralBand = band.getSpectralBandIndex() > -1;
            if (isSpectralBand && bandName.startsWith(reflectancesPrefix)) {
                final float currentWavelengthDist = Math.abs(band.getSpectralWavelength() - wavelength);
                if (currentWavelengthDist < wavelengthDist && currentWavelengthDist < maxDistance) {
                    wavelengthDist = currentWavelengthDist;
                    bestBandName = bandName;
                }
            }
        }
        if (bestBandName == null) {
            throw new OperatorException(
                    String.format("Not able to find band with prefix '%s' and wavelength '%4.3f'.",
                                  reflectancesPrefix, wavelength));
        }
        return bestBandName;
    }

    @Override
    protected void configureTargetSamples(SampleConfigurer sampleConfigurer) throws OperatorException {
        Band[] bands = getTargetProduct().getBands();
        int targetSampleIndex = 0;
        for (Band band : bands) {
            if (mustDefineTargetSample(band)) {
                sampleConfigurer.defineSample(targetSampleIndex, band.getName());
                targetSampleIndex++;
            }
        }
    }

    private boolean mustDefineTargetSample(Band band) {
        return !band.isSourceImageSet();
    }

    @Override
    protected void computePixel(int x, int y, Sample[] sourceSamples, WritableSample[] targetSamples) {
        if (sourceSamples.length != bandCount) {
            throw new OperatorException("Wrong number of source samples: Expected: " + bandCount +
                                        ", Actual: " + sourceSamples.length);
        }

        if (!areSourceSamplesValid(x, y, sourceSamples)) {
            for (int i = 0; i < CLASS_COUNT; i++) {
                targetSamples[i].set(Double.NaN);
            }
            targetSamples[9].set(-1);
            targetSamples[10].set(Double.NaN);
            return;
        }

        double[] rrsBelowWater = new double[bandCount];
        for (int i = 0; i < bandCount; i++) {
            rrsBelowWater[i] = convertToSubsurfaceWaterRrs(sourceSamples[i].getDouble());
        }
        double[] membershipIndicators = fuzzyClassification.computeClassMemberships(rrsBelowWater);

        // setting the values for the first 8 classes
        for (int i = 0; i < 8; i++) {
            WritableSample targetSample = targetSamples[i];
            final double membershipIndicator = membershipIndicators[i];
            targetSample.set(membershipIndicator);
        }

        // setting the value for the 9th class to the sum of the last 8 classes
        double ninthClassValue = 0.0;
        for (int i = 8; i < membershipIndicators.length; i++) {
            ninthClassValue += membershipIndicators[i];
        }
        targetSamples[8].set(ninthClassValue);

        // setting the value for dominant class, which is the max value of all other classes
        // setting the value for class sum, which is the sum of all other classes
        int dominantClass = -1;
        double dominantClassValue = Double.MIN_VALUE;
        double classSum = 0.0;
        for (int i = 0; i < 9; i++) {
            final double currentClassValue = targetSamples[i].getDouble();
            if (currentClassValue > dominantClassValue) {
                dominantClassValue = currentClassValue;
                dominantClass = i + 1;
            }
            classSum += currentClassValue;
        }
        targetSamples[CLASS_COUNT].set(dominantClass);
        targetSamples[CLASS_COUNT + 1].set(classSum);
    }

    private boolean areSourceSamplesValid(int x, int y, Sample[] sourceSamples) {
        if (!sourceProduct.containsPixel(x, y)) {
            return false;
        }
        for (Sample sourceSample : sourceSamples) {
            if (!sourceSample.getNode().isPixelValid(x, y)) {
                return false;
            }
        }
        return true;
    }

    // todo - conversion should be configurable
    // should be discussed with CB, KS
    private double convertToSubsurfaceWaterRrs(double merisL2Reflec) {
        // convert to remote sensing reflectances
        final double rrsAboveWater = merisL2Reflec / Math.PI;
        // convert to subsurface water remote sensing reflectances
        return rrsAboveWater / (0.52 + 1.7 * rrsAboveWater);
    }

    public static class Spi extends OperatorSpi {

        public Spi() {
            super(FuzzyOp.class);
        }
    }
}
