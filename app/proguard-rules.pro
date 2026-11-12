-keepattributes SourceFile,LineNumberTable

-dontwarn org.w3c.dom.DOMImplementationSourceList
-dontwarn org.xml.sax.driver
-dontwarn sharpen.config.MappingConfiguration

-keep class com.artifex.mupdf.fitz.** { *; }

-keep class com.itextpdf.bouncycastle.BouncyCastleFactory { public <init>(); }
-keepnames class * extends com.itextpdf.commons.actions.AbstractITextEvent
-keep class com.itextpdf.kernel.utils.RegisterDefaultDiContainer { *; }
-keep class com.itextpdf.forms.util.RegisterDefaultDiContainer { *; }
-keep class com.itextpdf.layout.utils.RegisterDefaultDiContainer { *; }

-keep class org.apache.xerces.dom.CoreDocumentImpl { <init>(...); }
-keep class org.apache.xerces.dom.DocumentImpl { <init>(...); }
-keep class org.apache.xerces.dom.PSVIDocumentImpl { <init>(...); }
-keep class org.apache.xerces.impl.dtd.XML11DTDProcessor { <init>(...); }
-keep class org.apache.xerces.impl.dtd.XML11DTDValidator { <init>(...); }
-keep class org.apache.xerces.impl.dtd.XMLDTDLoader { <init>(...); }
-keep class org.apache.xerces.impl.dtd.XMLDTDValidator { <init>(...); }
-keep class org.apache.xerces.impl.dv.dtd.DTDDVFactoryImpl { <init>(...); }
-keep class org.apache.xerces.impl.dv.dtd.XML11DTDDVFactoryImpl { <init>(...); }
-keep class org.apache.xerces.impl.dv.xs.ExtendedSchemaDVFactoryImpl { <init>(...); }
-keep class org.apache.xerces.impl.dv.xs.SchemaDVFactoryImpl { <init>(...); }
-keep class org.apache.xerces.impl.xs.XMLSchemaLoader { <init>(...); }
-keep class org.apache.xerces.impl.xs.XMLSchemaValidator { <init>(...); }
-keep class org.apache.xerces.impl.xs.XSMessageFormatter { <init>(...); }
-keep class org.apache.xerces.parsers.XIncludeAwareParserConfiguration { <init>(...); }
-keep class org.apache.xerces.parsers.XIncludeParserConfiguration { <init>(...); }
-keep class org.apache.xerces.parsers.XML11DTDConfiguration { <init>(...); }
-keep class org.apache.xerces.parsers.XPointerParserConfiguration { <init>(...); }
