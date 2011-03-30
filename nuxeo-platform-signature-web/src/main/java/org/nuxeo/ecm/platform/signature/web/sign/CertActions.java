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
package org.nuxeo.ecm.platform.signature.web.sign;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.security.Principal;

import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.faces.validator.ValidatorException;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.seam.ScopeType;
import org.jboss.seam.annotations.In;
import org.jboss.seam.annotations.Name;
import org.jboss.seam.annotations.Scope;
import org.jboss.seam.faces.FacesMessages;
import org.jboss.seam.international.StatusMessage;
import org.nuxeo.ecm.core.api.ClientException;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoPrincipal;
import org.nuxeo.ecm.directory.sql.PasswordHelper;
import org.nuxeo.ecm.platform.signature.api.exception.CertException;
import org.nuxeo.ecm.platform.signature.api.pki.CertService;
import org.nuxeo.ecm.platform.signature.api.user.CUserService;
import org.nuxeo.ecm.platform.ui.web.api.NavigationContext;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.webapp.helpers.ResourcesAccessor;

/**
 * Certificate management actions exposed as a Seam component. Used for
 * launching certificate generation, storage and retrieving operations from low
 * level services. Allows verifying if a user certificate is already present.
 * 
 * @author <a href="mailto:ws@nuxeo.com">Wojciech Sulejman</a>
 * 
 */
@Name("certActions")
@Scope(ScopeType.CONVERSATION)
public class CertActions implements Serializable {

    private static final long serialVersionUID = 2L;

    private static final Log LOG = LogFactory.getLog(CertActions.class);

    private static final int MINIMUM_PASSWORD_LENGTH = 8;

    private static final String USER_FIELD_FIRSTNAME = "user:firstName";

    private static final String USER_FIELD_LASTNAME = "user:lastName";

    private static final String USER_FIELD_EMAIL = "user:email";

    @In(create = true)
    protected transient CertService certService;

    @In(create = true)
    protected transient CUserService cUserService;

    @In(create = true)
    protected transient NavigationContext navigationContext;

    @In(create = true, required = false)
    protected FacesMessages facesMessages;

    @In(create = true)
    protected ResourcesAccessor resourcesAccessor;

    @In(create = true, required = false)
    protected transient CoreSession documentManager;

    @In(create = true)
    protected transient NuxeoPrincipal currentUser;

    @In(create = true)
    protected transient UserManager userManager;

    protected DocumentModel certificate;

    private static final String ROOT_CERTIFICATE_FILE_NAME = "ROOT_CA_.crt";

    /**
     * Retrieves a user certificate and returns a certificate's document model
     * object
     * 
     * @param user
     * @return
     * @throws ClientException
     */
    public DocumentModel getCertificate(DocumentModel user)
            throws ClientException {
        String userID = (String) user.getPropertyValue("user:username");
        return cUserService.getCertificate(userID);
    }

    /**
     * Checks if a specified user has a certificate
     * 
     * @param user
     * @return
     * @throws ClientException
     */
    public boolean hasCertificate(DocumentModel user) throws ClientException {
        String userID = (String) user.getPropertyValue("user:username");
        return cUserService.hasCertificate(userID);
    }

    /**
     * Checks if a specified user has a certificate
     * 
     * @param username
     * @return
     * @throws ClientException
     */
    public boolean hasCertificate(String username) throws ClientException {
        return cUserService.hasCertificate(username);
    }

    /**
     * Checks if a specified user has a certificate
     * 
     * @return
     * @throws ClientException
     */
    public boolean hasCertificate() throws ClientException {
        Principal currentUser = FacesContext.getCurrentInstance().getExternalContext().getUserPrincipal();
        DocumentModel user = userManager.getUserModel(currentUser.getName());
        return hasCertificate(user);
    }

    /**
     * Indicates whether a user has the right to generate a certificate.
     * 
     * @param user
     * @return
     * @throws ClientException
     */
    public boolean canGenerateCertificate(DocumentModel user)
            throws ClientException {
        boolean canGenerateCertificate = false;
        Principal currentUser = FacesContext.getCurrentInstance().getExternalContext().getUserPrincipal();
        canGenerateCertificate = currentUser.getName().equals(
                (String) user.getPropertyValue("user:username"));
        return canGenerateCertificate;
    }

    /**
     * Launches certificate generation. Requires valid passwords for certificate
     * encryption.
     * 
     * @param user
     * @param firstPassword
     * @param secondPassword
     */
    public void createCertificate(DocumentModel user, String firstPassword,
            String secondPassword) throws ClientException {
        boolean areRequirementsMet = false;

        try {
            validatePasswords(firstPassword, secondPassword);
            validateRequiredUserFields();
            // passed through validations
            areRequirementsMet = true;
        } catch (ValidatorException v) {
            facesMessages.add(StatusMessage.Severity.INFO,
                    v.getFacesMessage().getDetail());
        }

        if (areRequirementsMet) {
            try {
                cUserService.createCertificate(user, firstPassword);
                facesMessages.add(StatusMessage.Severity.INFO,
                        resourcesAccessor.getMessages().get(
                                "label.cert.created"));
            } catch (CertException e) {
                LOG.error(e);
                facesMessages.add(StatusMessage.Severity.ERROR,
                        resourcesAccessor.getMessages().get(
                                "label.cert.generate.problem"));
            } catch (ClientException e) {
                LOG.error(e);
                facesMessages.add(StatusMessage.Severity.ERROR,
                        resourcesAccessor.getMessages().get(
                                "label.cert.generate.problem"));
            }
        }
    }

    /**
     * Validates that the password follows business rules.
     * <p>
     * The password must be typed in twice correctly, follow minimum length, and
     * be different than the application login password.
     * <p>
     * The validations are performed in the following sequence cheapest
     * validations first, then the ones requiring more system resources.
     * 
     * @param firstPassword
     * @param secondPassword
     */
    public void validatePasswords(String firstPassword, String secondPassword)
            throws ClientException {

        DocumentModel user = userManager.getUserModel(currentUser.getName());

        if (firstPassword == null || secondPassword == null) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    resourcesAccessor.getMessages().get(
                            "label.review.added.reviewer"), null);
            LOG.warn(currentUser.getName() + " : " + message.getDetail());
            throw new ValidatorException(message);
        }

        if (!firstPassword.equals(secondPassword)) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    resourcesAccessor.getMessages().get(
                            "label.cert.password.mismatch"), null);
            LOG.warn(currentUser.getName() + " : " + message.getDetail());
            throw new ValidatorException(message);
        }

        // at least 8 characters
        if (firstPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    resourcesAccessor.getMessages().get(
                            "label.cert.password.too.short"), null);
            LOG.warn(currentUser.getName() + " : " + message.getDetail());
            throw new ValidatorException(message);
        }

        String hashedUserPassword = (String) user.getPropertyValue("user:password");

        /*
         * If the certificate password matches the user login password an
         * exception is thrown, as those passwords should not be the same.
         */
        if (PasswordHelper.verifyPassword(firstPassword, hashedUserPassword)) {

            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    resourcesAccessor.getMessages().get(
                            "label.cert.passwordSameAsLogin"), null);
            LOG.warn(currentUser.getName() + " : " + message.getDetail());
            throw new ValidatorException(message);
        }
    }

    /**
     * Validates user identity fields required for certificate generation
     * NXP-6485
     * <p>
     * 
     */
    public void validateRequiredUserFields() throws ClientException {

        DocumentModel user = userManager.getUserModel(currentUser.getName());
        // first name
        String firstName = (String) user.getPropertyValue(USER_FIELD_FIRSTNAME);
        if (null == firstName || firstName.length() == 0) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    resourcesAccessor.getMessages().get(
                            "label.cert.user.firstname.missing"), null);
            LOG.warn(currentUser.getName() + " : " + message.getDetail());
            throw new ValidatorException(message);
        }
        // last name
        String lastName = (String) user.getPropertyValue(USER_FIELD_LASTNAME);
        if (null == lastName || lastName.length() == 0) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    resourcesAccessor.getMessages().get(
                            "label.cert.user.lastname.missing"), null);
            LOG.warn(currentUser.getName() + " : " + message.getDetail());
            throw new ValidatorException(message);
        }
        // email - // a very forgiving check (e.g. accepts _@localhost)
        String email = (String) user.getPropertyValue(USER_FIELD_EMAIL);
        String emailRegex = ".+@.+";
        if (null == email || email.length() == 0 || !email.matches(emailRegex)) {
            FacesMessage message = new FacesMessage(
                    FacesMessage.SEVERITY_ERROR,
                    resourcesAccessor.getMessages().get(
                            "label.cert.user.email.problem"), null);
            LOG.warn(currentUser.getName() + " : " + message.getDetail());
            throw new ValidatorException(message);
        }
    }

    public void downloadRootCertificate() throws CertException {
        try {
            byte[] rootCertificateData = cUserService.getRootCertificateData();
            HttpServletResponse response = (HttpServletResponse) FacesContext.getCurrentInstance().getExternalContext().getResponse();
            response.setContentType("application/octet-stream");
            response.addHeader("Content-Disposition", "attachment;filename="
                    + ROOT_CERTIFICATE_FILE_NAME);
            response.setContentLength(rootCertificateData.length);
            OutputStream writer = response.getOutputStream();
            writer.write(rootCertificateData);
            writer.flush();
            writer.close();
            FacesContext.getCurrentInstance().responseComplete();
        } catch (ClientException e) {
            throw new CertException(e);
        } catch (IOException e) {
            throw new CertException(e);
        }
    }
}