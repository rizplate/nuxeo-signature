/*
 * (C) Copyright 2015 Nuxeo SA (http://nuxeo.com/) and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Vincent Dutat
 */
package org.nuxeo.ecm.platform.signature.core.sign;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.platform.signature.api.sign.SignatureAppearanceFactory;
import org.nuxeo.ecm.platform.signature.api.sign.SignatureLayout;
import org.nuxeo.ecm.platform.signature.api.sign.SignatureService;
import org.nuxeo.runtime.api.Framework;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.pdf.PdfSignatureAppearance;

public class DefaultSignatureAppearanceFactory implements SignatureAppearanceFactory {

    protected static final Log LOGGER = LogFactory.getLog(DefaultSignatureAppearanceFactory.class);

    @Override
    public void format(PdfSignatureAppearance pdfSignatureAppearance, DocumentModel doc, String principal, String reason) {
        pdfSignatureAppearance.setReason(reason);
        pdfSignatureAppearance.setAcro6Layers(true);
        pdfSignatureAppearance.setRenderingMode(PdfSignatureAppearance.RenderingMode.DESCRIPTION);
        SignatureService service = Framework.getService(SignatureService.class);
        SignatureLayout layout = ((SignatureServiceImpl)service).getSignatureLayout();
        Font layer2Font = FontFactory.getFont(FontFactory.TIMES, (float) layout.getTextSize(), Font.NORMAL,
                BaseColor.BLACK);
        pdfSignatureAppearance.setLayer2Font(layer2Font);
    }

}
