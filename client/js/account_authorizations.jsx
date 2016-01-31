"use strict";
import * as models from './models';
import * as stream_manager from './stream_manager';
import * as application_model from './application_model';
import React from 'react';
import ReactDOM from 'react-dom';

var AppViewModel = function(user) {
    var self = this;
    application_model.AppViewModel.call(this, user);
};

$(function() {
    var model = new AppViewModel(
        application_model.initialUser());

    ko.applyBindings(model);
});

/**
    An authorized application.
*/
var Authorization = React.createClass({
    render() {
        const self = this;
        return (
            <tr>
                <td>{this.props.data.clientName}</td>
                <td>{this.props.data.issued}</td>
                <td>
                    <button onClick={function() {
                        self.props.onRevoke(self.props.data.clientId);
                    }}>
                        <span className="glyphicon glyphicon-remove"></span>
                    </button>
                </td>
            </tr>
        );
    }
});

/**
    List of authorizations.
*/
var AuthorizationList = React.createClass({
    getInitialState() {
        return { authorizations: [] }
    },
    componentDidMount() {
        $.ajax({
            type: "GET",
            url: jsRoutes.controllers.Account.authorizations().url,
            headers: {
                Accept: "application/json"
            }
        }).then(result => {
            this.setState({
                authorizations: result.map(x => ({
                    clientId: x.clientId,
                    clientName: x.clientName,
                    issued: x.issued
                }))
            });
        });
    },
    revokeAuthorization(id) {
        $.ajax({
            type: "DELETE",
            url: jsRoutes.controllers.Account.revokeAuthorization(id).url,
        }).then(() => {
            this.removeAuthorization(id);
        });
    },
    removeAuthorization(id) {
        return this.setState({
            'authorizations': this.state.authorizations.filter(x => x.clientId !== id)
        })
    },
    render() {
        const authorizationCells = this.state.authorizations.map(authorization => {
            return (
                <Authorization key={authorization.clientId} data={authorization} onRevoke={this.revokeAuthorization} />
            );
        });
        return (
            <table>
                <thead>
                    <tr>
                        <th>Name</th><th>Issued</th><th></th>
                    </tr>
                </thead>
                <tbody>{authorizationCells}</tbody>
            </table>
        );

    }
});

ReactDOM.render(
    <AuthorizationList data={authorizations} />,
    document.getElementById('authorizations'));