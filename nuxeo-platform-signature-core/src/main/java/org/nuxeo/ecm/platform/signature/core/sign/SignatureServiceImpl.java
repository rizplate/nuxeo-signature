/*
 * (C) Copyright 2011 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Wojciech Sulejman
 */

package org.nuxeo.ecm.platform.signature.core.sign;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.signature.api.exception.CertException;
import org.nuxeo.ecm.platform.signature.api.exception.SignException;
import org.nuxeo.ecm.platform.signature.api.pki.CertService;
import org.nuxeo.ecm.platform.signature.api.sign.SignatureService;
import org.nuxeo.ecm.platform.signature.api.user.AliasType;
import org.nuxeo.ecm.platform.signature.api.user.AliasWrapper;
import org.nuxeo.ecm.platform.signature.api.user.CUserService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.model.ComponentInstance;
import org.nuxeo.runtime.model.DefaultComponent;

import com.lowagie.text.DocumentException;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.AcroFields;
import com.lowagie.text.pdf.PdfPKCS7;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfSignatureAppearance;
import com.lowagie.text.pdf.PdfStamper;

/**
 * 
 * Base implementation for the signature service.
 * 
 * @author <a href="mailto:ws@nuxeo.com">Wojciech Sulejman</a>
 */
public class SignatureServiceImpl extends DefaultComponent implements
        SignatureService {
    private static final Log log = LogFactory.getLog(SignatureServiceImpl.class);

    private List<SignatureDescriptor> config = new ArrayList<SignatureDescriptor>();

    protected CertService certService;

    protected CUserService cUserService;

    public File signPDF(DocumentModel user, String keyPassword, String reason,
            InputStream origPdfStream) throws SignException {
        File outputFile = null;
        try {
            outputFile = File.createTempFile("signed-", ".pdf");
            PdfReader reader = new PdfReader(origPdfStream);

            PdfStamper stp = PdfStamper.createSignature(reader,
                    new FileOutputStream(outputFile), '\0');
            PdfSignatureAppearance sap = stp.getSignatureAppearance();

            String userID = (String) user.getPropertyValue("user:username");
            AliasWrapper alias = new AliasWrapper(userID);
            KeyStore keystore = getCUserService().getUserKeystore(userID,
                    keyPassword);
            Certificate certificate = getCertService().getCertificate(keystore,
                    alias.getId(AliasType.CERT));
            KeyPair keyPair = getCertService().getKeyPair(keystore,
                    alias.getId(AliasType.KEY), alias.getId(AliasType.CERT),
                    keyPassword);
            List<Certificate> certificates = new ArrayList<Certificate>();
            certificates.add(certificate);

            Certificate[] certChain = certificates.toArray(new Certificate[0]);
            sap.setCrypto(keyPair.getPrivate(), certChain, null,
                    PdfSignatureAppearance.SELF_SIGNED);
            if (null == reason || reason == "") {
                reason = getSigningReason();
            }
            sap.setReason(reason);
            sap.setCertificationLevel(PdfSignatureAppearance.CERTIFIED_NO_CHANGES_ALLOWED);
            sap.setVisibleSignature(new Rectangle(400, 450, 200, 200), 1,
                    "SOME NAME");
            sap.setAcro6Layers(true);
            sap.setRender(PdfSignatureAppearance.SignatureRenderNameAndDescription);
            stp.close();
            log.debug("File " + outputFile.getAbsolutePath()
                    + " created and signed with " + reason);

        } catch (UnrecoverableKeyException e) {
            throw new CertException(e);
        } catch (KeyStoreException e) {
            throw new CertException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new SignException(e);
        } catch (CertificateException e) {
            throw new SignException(e);
        } catch (FileNotFoundException e) {
            throw new SignException(e);
        } catch (IOException e) {
            throw new SignException(e);
        } catch (SignatureException e) {
            throw new SignException(e);
        } catch (DocumentException e) {
            throw new SignException(e);
        } catch (Exception e) {
            throw new SignException(e);
        }
        return outputFile;
    }

    @Override
    public List<X509Certificate> getPDFCertificates(InputStream pdfStream)
            throws SignException {
        List<X509Certificate> pdfCertificates = new ArrayList<X509Certificate>();
        try {
            PdfReader pdfReader = new PdfReader(pdfStream);
            AcroFields acroFields = pdfReader.getAcroFields();
            // get all signatures
            List signatureNames = acroFields.getSignatureNames();
            for (int k = 0; k < signatureNames.size(); ++k) {
                String signatureName = (String) signatureNames.get(k);
                PdfPKCS7 pdfPKCS7 = acroFields.verifySignature(signatureName);
                X509Certificate signingCertificate = pdfPKCS7.getSigningCertificate();
                pdfCertificates.add(signingCertificate);
            }
        } catch (IOException e) {
            String message="";
            if(e.getMessage().equals("PDF header signature not found.")){
                message="PDF seems to be corrupted";
            }
            throw new SignException(message,e);
        }
        return pdfCertificates;
    }

    protected CertService getCertService() throws Exception {
        if (certService == null) {
            certService = Framework.getService(CertService.class);
        }
        return certService;
    }

    protected CUserService getCUserService() throws Exception {
        if (cUserService == null) {
            cUserService = Framework.getService(CUserService.class);
        }
        return cUserService;
    }

    private String getSigningReason() throws SignatureException {
        String reason = null;
        for (SignatureDescriptor sd : config) {
            if (sd.getReason() != null) {
                reason = sd.getReason();
            }
        }
        if (reason == null) {
            throw new SignatureException(
                    "You have to provide a default reason in the extension point");
        }
        return reason;
    }

    @Override
    public void registerContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor) {
        config.add((SignatureDescriptor) contribution);
    }

    @Override
    public void unregisterContribution(Object contribution,
            String extensionPoint, ComponentInstance contributor) {
        config.remove(contribution);
    }

}