
package com.android.sdklib.repositoryv2.generated.repository.v1;

import javax.xml.bind.JAXBElement;
import javax.xml.bind.annotation.XmlElementDecl;
import javax.xml.bind.annotation.XmlRegistry;
import javax.xml.namespace.QName;
import com.android.repository.api.Repository;
import com.android.repository.impl.generated.v1.RepositoryType;
import com.android.sdklib.repositoryv2.meta.RepoFactory;


/**
 * DO NOT EDIT
 * This file was generated by xjc from sdk-repository-01.xsd. Any changes will be lost upon recompilation of the schema.
 * See the schema file for instructions on running xjc.
 * 
 * This object contains factory methods for each 
 * Java content interface and Java element interface 
 * generated in the com.android.sdklib.repositoryv2.generated.repository.v1 package. 
 * <p>An ObjectFactory allows you to programatically 
 * construct new instances of the Java representation 
 * for XML content. The Java representation of XML 
 * content can consist of schema derived interfaces 
 * and classes representing the binding of schema 
 * type definitions, element declarations and model 
 * groups.  Factory methods for each of these are 
 * provided in this class.
 * 
 */
@XmlRegistry
@SuppressWarnings("override")
public class ObjectFactory
    extends RepoFactory
{

    private final static QName _SdkRepository_QNAME = new QName("http://schemas.android.com/sdk/android/repo/repository2/01", "sdk-repository");

    /**
     * Create a new ObjectFactory that can be used to create new instances of schema derived classes for package: com.android.sdklib.repositoryv2.generated.repository.v1
     * 
     */
    public ObjectFactory() {
    }

    /**
     * Create an instance of {@link PlatformDetailsType }
     * 
     */
    public PlatformDetailsType createPlatformDetailsType() {
        return new PlatformDetailsType();
    }

    /**
     * Create an instance of {@link com.android.sdklib.repositoryv2.generated.repository.v1.LayoutlibType }
     * 
     */
    public com.android.sdklib.repositoryv2.meta.DetailsTypes.PlatformDetailsType.LayoutlibType createLayoutlibType() {
        return new com.android.sdklib.repositoryv2.generated.repository.v1.LayoutlibType();
    }

    /**
     * Create an instance of {@link SourceDetailsType }
     * 
     */
    public SourceDetailsType createSourceDetailsType() {
        return new SourceDetailsType();
    }

    /**
     * Create an instance of {@link JAXBElement }{@code <}{@link RepositoryType }{@code >}}
     * 
     */
    @XmlElementDecl(namespace = "http://schemas.android.com/sdk/android/repo/repository2/01", name = "sdk-repository")
    public JAXBElement<RepositoryType> createSdkRepository(RepositoryType value) {
        return new JAXBElement<RepositoryType>(_SdkRepository_QNAME, RepositoryType.class, null, value);
    }

    public JAXBElement<Repository> generateElement(Repository value) {
        return ((JAXBElement) createSdkRepository(((RepositoryType) value)));
    }

}
